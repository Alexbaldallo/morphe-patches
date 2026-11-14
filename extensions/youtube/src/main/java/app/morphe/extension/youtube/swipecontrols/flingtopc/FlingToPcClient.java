package app.morphe.extension.youtube.swipecontrols.flingtopc;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Sends the current video to the FlingPC receiver running on a computer of the same network.
 * <p>
 * The message is a small JSON datagram sent by UDP to port {@link #DEFAULT_PORT}. Without a
 * configured address it goes to the broadcast address of every network the phone is connected to,
 * so the computer does not need a fixed IP. The receiver answers with an acknowledgement that
 * carries the same nonce, which is how the phone knows the video arrived.
 */
public final class FlingToPcClient {

    public static final int DEFAULT_PORT = 47800;

    /**
     * Each round sends the datagram again and waits this long for the acknowledgement.
     */
    private static final int ROUND_TIMEOUT_MS = 300;

    private static final int ROUNDS = 4;

    public enum Failure {
        /** No receiver answered. */
        NOT_FOUND,
        /** A receiver answered but the pairing key does not match. */
        BAD_KEY,
        /** The datagram could not be sent at all. */
        NETWORK
    }

    public interface Callback {
        /**
         * Called on the main thread.
         *
         * @param receiverName Name of the computer that accepted the video, or null if it failed.
         * @param failure      Reason of the failure, or null if it succeeded.
         */
        void onResult(@Nullable String receiverName, @Nullable Failure failure);
    }

    private static final SecureRandom random = new SecureRandom();

    private FlingToPcClient() {
    }

    public static void send(String videoId, double seconds, boolean live, String title,
                            String host, String key, Callback callback) {
        Utils.runOnBackgroundThread(() -> {
            String name = null;
            Failure failure;
            try {
                name = sendBlocking(videoId, seconds, live, title, host, key);
                failure = name == null ? Failure.NOT_FOUND : null;
            } catch (BadKeyException ex) {
                failure = Failure.BAD_KEY;
            } catch (Exception ex) {
                Logger.printException(() -> "Fling to PC failed", ex);
                failure = Failure.NETWORK;
            }

            final String finalName = name;
            final Failure finalFailure = failure;
            Utils.runOnMainThread(() -> callback.onResult(finalName, finalFailure));
        });
    }

    /**
     * Pauses the video on the phone, using the same media key the background playback patch uses.
     */
    public static void pauseLocalPlayback(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        final long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
    }

    private static final class BadKeyException extends IOException {
    }

    /**
     * @return The name of the computer that accepted the video, or null if nothing answered.
     */
    @Nullable
    private static String sendBlocking(String videoId, double seconds, boolean live, String title,
                                       String host, String key) throws IOException, JSONException {
        final String nonce = Long.toHexString(random.nextLong());

        JSONObject message = new JSONObject();
        message.put("app", "flingpc");
        message.put("v", 1);
        message.put("cmd", "play");
        message.put("id", videoId);
        message.put("t", seconds);
        message.put("live", live);
        message.put("title", title == null ? "" : title);
        message.put("key", key);
        message.put("n", nonce);
        final byte[] data = message.toString().getBytes(StandardCharsets.UTF_8);

        List<InetSocketAddress> targets = resolveTargets(host);
        if (targets.isEmpty()) {
            throw new IOException("No network to send to");
        }

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            byte[] buffer = new byte[2048];
            boolean sentAny = false;

            for (int round = 0; round < ROUNDS; round++) {
                for (InetSocketAddress target : targets) {
                    try {
                        socket.send(new DatagramPacket(data, data.length, target));
                        sentAny = true;
                    } catch (IOException ex) {
                        // One interface can refuse broadcasts while another works.
                        Logger.printDebug(() -> "Fling to PC could not send to " + target + ": " + ex);
                    }
                }
                if (!sentAny) {
                    throw new IOException("Could not send to any address");
                }

                final long deadline = SystemClock.elapsedRealtime() + ROUND_TIMEOUT_MS;
                while (true) {
                    final long left = deadline - SystemClock.elapsedRealtime();
                    if (left <= 0) break;
                    socket.setSoTimeout((int) left);

                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(packet);
                    } catch (SocketTimeoutException ex) {
                        break;
                    }

                    JSONObject reply;
                    try {
                        reply = new JSONObject(new String(packet.getData(), packet.getOffset(),
                                packet.getLength(), StandardCharsets.UTF_8));
                    } catch (JSONException ex) {
                        continue; // Not ours.
                    }
                    if (!nonce.equals(reply.optString("n"))) {
                        continue; // Reply to an older message.
                    }
                    if (reply.optBoolean("ok")) {
                        return reply.optString("name", packet.getAddress().getHostAddress());
                    }
                    if ("bad_key".equals(reply.optString("error"))) {
                        throw new BadKeyException();
                    }
                }
            }
        }

        return null;
    }

    /**
     * The configured address, or the broadcast address of every IPv4 network the phone is on.
     *
     * @param host Empty, a host name or IP, optionally followed by ':port'.
     */
    private static List<InetSocketAddress> resolveTargets(String host) throws IOException {
        String trimmed = host == null ? "" : host.trim();
        if (!trimmed.isEmpty()) {
            int port = DEFAULT_PORT;
            String name = trimmed;
            final int colon = trimmed.lastIndexOf(':');
            if (colon > 0 && trimmed.indexOf(':') == colon) {
                try {
                    port = Integer.parseInt(trimmed.substring(colon + 1).trim());
                    name = trimmed.substring(0, colon).trim();
                } catch (NumberFormatException ignored) {
                    // Not a port, use the whole value as the host.
                }
            }
            return Collections.singletonList(new InetSocketAddress(InetAddress.getByName(name), port));
        }

        Set<InetAddress> addresses = new LinkedHashSet<>();
        for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            try {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
            } catch (IOException ex) {
                continue;
            }
            for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                InetAddress broadcast = interfaceAddress.getBroadcast();
                if (broadcast instanceof Inet4Address) {
                    addresses.add(broadcast);
                }
            }
        }
        // Some devices report no broadcast address for Wi-Fi.
        addresses.add(InetAddress.getByName("255.255.255.255"));

        List<InetSocketAddress> targets = new ArrayList<>(addresses.size());
        for (InetAddress address : addresses) {
            targets.add(new InetSocketAddress(address, DEFAULT_PORT));
        }
        return targets;
    }
}
