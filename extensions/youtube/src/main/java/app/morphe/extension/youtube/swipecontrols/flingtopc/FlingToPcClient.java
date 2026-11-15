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
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Talks to the FlingPC receiver running on a computer of the same network.
 * <p>
 * Every message is a small JSON datagram sent by UDP to port {@link #DEFAULT_PORT}. To send a
 * video without a configured address it goes to the broadcast address of every network the
 * phone is on, so the computer does not need a fixed IP. Later commands go straight to the
 * computer that answered. The receiver answers every datagram with the same nonce, plus the
 * state of its player, and a datagram is sent again until that answer arrives.
 */
public final class FlingToPcClient {

    public static final int DEFAULT_PORT = 47800;

    public enum Failure {
        /** No receiver answered. */
        NOT_FOUND,
        /** A receiver answered but the pairing key does not match. */
        BAD_KEY,
        /** The datagram could not be sent at all, see the detail. */
        NETWORK
    }

    /** The answer of the computer. */
    public static final class Reply {
        public final InetSocketAddress address;
        public final String name;
        /** State of the player on the computer, or null if it was not sent. */
        @Nullable
        public final JSONObject state;

        Reply(InetSocketAddress address, String name, @Nullable JSONObject state) {
            this.address = address;
            this.name = name;
            this.state = state;
        }
    }

    public interface Callback {
        /**
         * Called on the main thread.
         *
         * @param reply   The answer, or null if it failed.
         * @param failure Reason of the failure, or null if it succeeded.
         * @param detail  Error text of the system for {@link Failure#NETWORK}, otherwise null.
         */
        void onResult(@Nullable Reply reply, @Nullable Failure failure, @Nullable String detail);
    }

    private interface Targets {
        List<InetSocketAddress> resolve() throws IOException;
    }

    private static final SecureRandom random = new SecureRandom();

    private FlingToPcClient() {
    }

    /**
     * Shows a video on the computer.
     *
     * @param host Empty to look for the computer on the network, or its host name or IP.
     */
    public static void play(String videoId, double seconds, boolean live, String title,
                            String host, String key, Callback callback) {
        JSONObject message = new JSONObject();
        try {
            message.put("id", videoId);
            message.put("t", seconds);
            message.put("live", live);
            message.put("title", title == null ? "" : title);
        } catch (JSONException ex) {
            throw new IllegalStateException(ex);
        }
        // Searching the network can take a few retries.
        exchangeAsync("play", message, key, () -> resolveTargets(host), 4, 300, callback);
    }

    /**
     * Sends a command of the remote to the computer that accepted the video.
     *
     * @param extras Arguments of the command, or null.
     */
    public static void command(InetSocketAddress computer, String key, String command,
                               @Nullable JSONObject extras, Callback callback) {
        JSONObject message = new JSONObject();
        try {
            if (extras != null) {
                for (Iterator<String> it = extras.keys(); it.hasNext(); ) {
                    String name = it.next();
                    message.put(name, extras.get(name));
                }
            }
        } catch (JSONException ex) {
            throw new IllegalStateException(ex);
        }
        exchangeAsync(command, message, key, () -> Collections.singletonList(computer), 3, 250, callback);
    }

    /**
     * Pauses the video on the phone, using the same media key the background playback patch uses.
     */
    public static void pauseLocalPlayback(Context context) {
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE);
    }

    /**
     * Resumes the video on the phone. Only reaches YouTube if no other media session is active.
     */
    public static void playLocalPlayback(Context context) {
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY);
    }

    private static void dispatchMediaKey(Context context, int keyCode) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        final long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private static final class BadKeyException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static void exchangeAsync(String command, JSONObject message, String key, Targets targets,
                                      int rounds, int roundTimeoutMs, Callback callback) {
        Utils.runOnBackgroundThread(() -> {
            Reply reply = null;
            Failure failure = null;
            String detail = null;
            try {
                message.put("app", "flingpc");
                message.put("v", 1);
                message.put("cmd", command);
                message.put("key", key);
                reply = exchange(message, targets.resolve(), rounds, roundTimeoutMs);
                if (reply == null) failure = Failure.NOT_FOUND;
            } catch (BadKeyException ex) {
                failure = Failure.BAD_KEY;
            } catch (Exception ex) {
                Logger.printInfo(() -> "Fling to PC could not send " + command, ex);
                failure = Failure.NETWORK;
                detail = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            }

            final Reply finalReply = reply;
            final Failure finalFailure = failure;
            final String finalDetail = detail;
            Utils.runOnMainThread(() -> callback.onResult(finalReply, finalFailure, finalDetail));
        });
    }

    /**
     * @return The answer of the computer, or null if nothing answered.
     */
    @Nullable
    private static Reply exchange(JSONObject message, List<InetSocketAddress> targets,
                                  int rounds, int roundTimeoutMs) throws IOException, JSONException {
        if (targets.isEmpty()) {
            throw new IOException("No network to send to");
        }

        final String nonce = Long.toHexString(random.nextLong());
        message.put("n", nonce);
        final byte[] data = message.toString().getBytes(StandardCharsets.UTF_8);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            byte[] buffer = new byte[4096];

            for (int round = 0; round < rounds; round++) {
                IOException lastSendError = null;
                boolean sentAny = false;
                for (InetSocketAddress target : targets) {
                    try {
                        socket.send(new DatagramPacket(data, data.length, target));
                        sentAny = true;
                    } catch (IOException ex) {
                        // One interface can refuse broadcasts while another works.
                        lastSendError = ex;
                    }
                }
                if (!sentAny) {
                    // Keep the reason of the system, for example EPERM when the local network is blocked.
                    throw lastSendError != null ? lastSendError : new IOException("Could not send");
                }

                final long deadline = SystemClock.elapsedRealtime() + roundTimeoutMs;
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
                        continue; // Answer to an older message.
                    }
                    if (reply.optBoolean("ok")) {
                        InetSocketAddress from = new InetSocketAddress(packet.getAddress(), packet.getPort());
                        String name = reply.optString("name", packet.getAddress().getHostAddress());
                        return new Reply(from, name, reply.optJSONObject("state"));
                    }
                    if ("bad_key".equals(reply.optString("error"))) {
                        throw new BadKeyException();
                    }
                    throw new IOException("The PC refused the command: " + reply.optString("error"));
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
