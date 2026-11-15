package app.morphe.extension.youtube.swipecontrols.flingtopc;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;

/**
 * Remote for the video playing on the computer.
 * <p>
 * A media session and its notification, so the controls appear with the other media controls,
 * in the notifications and on the lock screen: play and pause, the seek bar, 10 seconds back and
 * forward, continue on the phone and remove from the PC. The volume keys of the phone change the
 * volume of the computer while the session is active. The state is read from the computer every
 * few seconds, so the controls follow what happens there.
 */
public final class FlingToPcRemote {

    static final int NOTIFICATION_ID = 47800;
    private static final String CHANNEL_ID = "morphe_flingpc";

    private static final String ACTION_REWIND = "app.morphe.flingpc.REWIND";
    private static final String ACTION_FORWARD = "app.morphe.flingpc.FORWARD";
    private static final String ACTION_TOGGLE = "app.morphe.flingpc.TOGGLE";
    private static final String ACTION_BACK = "app.morphe.flingpc.BACK";
    private static final String ACTION_CLOSE = "app.morphe.flingpc.CLOSE";
    private static final String ACTION_DISMISS = "app.morphe.flingpc.DISMISS";

    private static final double SKIP_SECONDS = 10;
    private static final long POLL_INTERVAL_MS = 2500;
    /** About 10 seconds without answers. */
    private static final int MAX_FAILED_POLLS = 4;
    /** The volume keys move the volume of the computer in steps of 5%. */
    private static final int VOLUME_STEPS = 20;

    @Nullable
    private static FlingToPcRemote current;

    private final Context context;
    private final InetSocketAddress computer;
    private final String computerName;
    private final String key;
    private final String videoId;
    private final String title;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaSession session;
    private VolumeProvider volumeProvider;
    private final BroadcastReceiver actionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onAction(intent.getAction());
        }
    };

    private boolean released;
    private boolean playing = true;
    private boolean buffering = true;
    /** Seconds, at {@link #positionAt}. */
    private double position;
    private long positionAt;
    private double duration;
    private int failedPolls;
    @Nullable
    private Bitmap artwork;
    private boolean notifiedPlaying;

    private final Runnable pollRunnable = this::poll;

    /**
     * Shows the remote for a video the computer accepted. Replaces the previous remote.
     *
     * @param seconds  Time the video was sent at.
     * @param duration Length of the video in seconds, or 0 if not known.
     */
    public static void start(Context context, FlingToPcClient.Reply reply, String key,
                             String videoId, String title, double seconds, double duration) {
        try {
            stopCurrent();
            current = new FlingToPcRemote(context.getApplicationContext(), reply, key, videoId, title, seconds, duration);
            current.begin();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show the FlingPC remote", ex);
            if (current != null) current.release();
        }
    }

    public static void stopCurrent() {
        if (current != null) current.release();
    }

    @Nullable
    static FlingToPcRemote getCurrent() {
        return current;
    }

    private FlingToPcRemote(Context context, FlingToPcClient.Reply reply, String key, String videoId,
                            String title, double seconds, double duration) {
        this.context = context;
        this.computer = reply.address;
        this.computerName = reply.name;
        this.key = key;
        this.videoId = videoId;
        this.title = title == null || title.isEmpty() ? "YouTube" : title;
        this.position = seconds;
        this.positionAt = SystemClock.elapsedRealtime();
        this.duration = duration;
    }

    private void begin() {
        createChannel(context);

        volumeProvider = new VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, VOLUME_STEPS, VOLUME_STEPS) {
            @Override
            public void onSetVolumeTo(int volume) {
                setVolume(volume);
            }

            @Override
            public void onAdjustVolume(int direction) {
                setVolume(getCurrentVolume() + direction);
            }
        };

        session = new MediaSession(context, "FlingPC");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                setPlaying(true);
                send("resume", null);
            }

            @Override
            public void onPause() {
                setPlaying(false);
                send("pause", null);
            }

            @Override
            public void onStop() {
                closeOnComputer();
            }

            @Override
            public void onSeekTo(long positionMs) {
                seekTo(positionMs / 1000.0);
            }

            @Override
            public void onFastForward() {
                seekTo(currentPosition() + SKIP_SECONDS);
            }

            @Override
            public void onRewind() {
                seekTo(currentPosition() - SKIP_SECONDS);
            }

            @Override
            public void onCustomAction(String action, @Nullable Bundle extras) {
                onAction(action);
            }
        }, handler);
        session.setPlaybackToRemote(volumeProvider);
        PendingIntent openApp = openAppIntent();
        if (openApp != null) session.setSessionActivity(openApp);
        updateMetadata();
        updatePlaybackState();
        session.setActive(true);

        IntentFilter filter = new IntentFilter();
        for (String action : new String[]{ACTION_REWIND, ACTION_FORWARD, ACTION_TOGGLE, ACTION_BACK, ACTION_CLOSE, ACTION_DISMISS}) {
            filter.addAction(action);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(actionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(actionReceiver, filter);
        }

        notifiedPlaying = playing;
        FlingToPcService.start(context);
        notificationManager().notify(NOTIFICATION_ID, buildNotification());

        loadArtwork();
        handler.postDelayed(pollRunnable, 800);
    }

    // region Commands

    private void onAction(@Nullable String action) {
        if (released || action == null) return;
        switch (action) {
            case ACTION_REWIND -> seekTo(currentPosition() - SKIP_SECONDS);
            case ACTION_FORWARD -> seekTo(currentPosition() + SKIP_SECONDS);
            case ACTION_TOGGLE -> {
                setPlaying(!playing);
                send(playing ? "resume" : "pause", null);
            }
            case ACTION_BACK -> continueOnPhone();
            case ACTION_CLOSE -> closeOnComputer();
            case ACTION_DISMISS -> release(); // The video keeps playing on the computer.
            default -> {
            }
        }
    }

    private void setPlaying(boolean nowPlaying) {
        position = currentPosition();
        positionAt = SystemClock.elapsedRealtime();
        playing = nowPlaying;
        updatePlaybackState();
        updateNotificationIfNeeded();
    }

    private void seekTo(double seconds) {
        double target = Math.max(0, seconds);
        if (duration > 0) target = Math.min(target, duration - 1);
        position = target;
        positionAt = SystemClock.elapsedRealtime();
        updatePlaybackState();
        send("seek", extras("t", target));
    }

    private void setVolume(int steps) {
        final int clamped = Math.max(0, Math.min(VOLUME_STEPS, steps));
        volumeProvider.setCurrentVolume(clamped);
        send("volume", extras("level", clamped * 100 / VOLUME_STEPS));
    }

    private void closeOnComputer() {
        send("close", null);
        release();
        Utils.showToastShort(str("morphe_fling_to_pc_closed"));
    }

    /**
     * Removes the video from the computer and goes on with it on the phone, at the same time.
     */
    private void continueOnPhone() {
        final double seconds = currentPosition();
        send("close", null);
        release();

        if (!videoId.equals(VideoInformation.getVideoId())) {
            Utils.showToastLong(str("morphe_fling_to_pc_back_other"));
            return;
        }
        try {
            VideoInformation.seekTo((long) (seconds * 1000));
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not seek on the phone", ex);
        }
        // Once this session is gone the media key reaches YouTube again.
        Utils.runOnMainThreadDelayed(() -> FlingToPcClient.playLocalPlayback(context), 200);
    }

    private void send(String command, @Nullable JSONObject extras) {
        if (computer == null) return;
        // A command still goes out after release, the computer must get "close".
        FlingToPcClient.command(computer, key, command, extras, (reply, failure, detail) -> {
            if (released) return;
            if (failure == null) {
                failedPolls = 0;
                if (reply != null && reply.state != null) applyState(reply.state);
            } else if (failure == FlingToPcClient.Failure.BAD_KEY) {
                Utils.showToastLong(str("morphe_fling_to_pc_bad_key"));
                release();
            } else {
                Utils.showToastShort(str("morphe_fling_to_pc_not_found"));
            }
        });
    }

    private void poll() {
        if (released) return;
        FlingToPcClient.command(computer, key, "status", null, (reply, failure, detail) -> {
            if (released) return;
            if (failure == null) {
                failedPolls = 0;
                if (reply != null && reply.state != null) applyState(reply.state);
            } else if (failure == FlingToPcClient.Failure.BAD_KEY || ++failedPolls >= MAX_FAILED_POLLS) {
                Utils.showToastLong(str("morphe_fling_to_pc_disconnected"));
                release();
                return;
            }
            if (!released) handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
        });
    }

    /**
     * @param state State sent by the computer, see protocol.js of FlingPC.
     */
    private void applyState(JSONObject state) {
        if (!state.optBoolean("open")) {
            // Closed on the computer, with Esc or its close button.
            release();
            return;
        }
        playing = state.optBoolean("playing", playing);
        buffering = state.optBoolean("buffering", false);
        position = state.optDouble("t", position);
        positionAt = SystemClock.elapsedRealtime();

        final double newDuration = state.optDouble("duration", 0);
        if (newDuration > 0 && Math.abs(newDuration - duration) > 0.5) {
            duration = newDuration;
            updateMetadata();
        }

        final int volume = state.optBoolean("muted") ? 0 : state.optInt("volume", 100);
        volumeProvider.setCurrentVolume(Math.round(volume * VOLUME_STEPS / 100f));

        updatePlaybackState();
        updateNotificationIfNeeded();
    }

    private double currentPosition() {
        double seconds = position;
        if (playing && !buffering) {
            seconds += (SystemClock.elapsedRealtime() - positionAt) / 1000.0;
        }
        return duration > 0 ? Math.min(seconds, duration) : seconds;
    }

    private void release() {
        if (released) return;
        released = true;
        handler.removeCallbacksAndMessages(null);
        try {
            context.unregisterReceiver(actionReceiver);
        } catch (Exception ignored) {
            // Not registered yet.
        }
        if (session != null) {
            session.setActive(false);
            session.release();
        }
        FlingToPcService.stop();
        notificationManager().cancel(NOTIFICATION_ID);
        if (current == this) current = null;
    }

    // endregion

    // region Session and notification

    private void updatePlaybackState() {
        if (session == null || released) return;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND;
        if (duration > 0) actions |= PlaybackState.ACTION_SEEK_TO;

        final int state = !playing ? PlaybackState.STATE_PAUSED
                : buffering ? PlaybackState.STATE_BUFFERING
                : PlaybackState.STATE_PLAYING;

        // The custom actions fill the free slots of the media controls, in this order.
        PlaybackState.Builder builder = new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, (long) (position * 1000), playing && !buffering ? 1f : 0f, positionAt)
                .addCustomAction(customAction(ACTION_REWIND, "morphe_fling_to_pc_rewind", android.R.drawable.ic_media_rew))
                .addCustomAction(customAction(ACTION_FORWARD, "morphe_fling_to_pc_forward", android.R.drawable.ic_media_ff))
                .addCustomAction(customAction(ACTION_BACK, "morphe_fling_to_pc_back", android.R.drawable.ic_menu_revert))
                .addCustomAction(customAction(ACTION_CLOSE, "morphe_fling_to_pc_close", android.R.drawable.ic_menu_close_clear_cancel));
        session.setPlaybackState(builder.build());
    }

    private static PlaybackState.CustomAction customAction(String action, String nameKey, int icon) {
        return new PlaybackState.CustomAction.Builder(action, str(nameKey), icon).build();
    }

    private void updateMetadata() {
        if (session == null || released) return;
        MediaMetadata.Builder builder = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, str("morphe_fling_to_pc_sent", computerName));
        if (duration > 0) builder.putLong(MediaMetadata.METADATA_KEY_DURATION, (long) (duration * 1000));
        if (artwork != null) builder.putBitmap(MediaMetadata.METADATA_KEY_ART, artwork);
        session.setMetadata(builder.build());
    }

    private void updateNotificationIfNeeded() {
        if (released || notifiedPlaying == playing) return;
        notifiedPlaying = playing;
        notificationManager().notify(NOTIFICATION_ID, buildNotification());
    }

    Notification buildNotification() {
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(str("morphe_fling_to_pc_sent", computerName))
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(playing)
                .setDeleteIntent(actionIntent(ACTION_DISMISS))
                // Android 12 and older use these buttons, newer versions the ones of the session.
                .addAction(action(android.R.drawable.ic_media_rew, "morphe_fling_to_pc_rewind", ACTION_REWIND))
                .addAction(action(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        "morphe_fling_to_pc_play_pause", ACTION_TOGGLE))
                .addAction(action(android.R.drawable.ic_media_ff, "morphe_fling_to_pc_forward", ACTION_FORWARD))
                .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "morphe_fling_to_pc_close", ACTION_CLOSE))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        PendingIntent openApp = openAppIntent();
        if (openApp != null) builder.setContentIntent(openApp);
        if (artwork != null) builder.setLargeIcon(artwork);
        return builder.build();
    }

    /** For the service, if it starts after the remote already ended. */
    static Notification buildPlaceholderNotification(Context context) {
        createChannel(context);
        return new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("FlingPC")
                .build();
    }

    private Notification.Action action(int icon, String titleKey, String action) {
        return new Notification.Action.Builder(Icon.createWithResource(context, icon), str(titleKey),
                actionIntent(action)).build();
    }

    private PendingIntent actionIntent(String action) {
        Intent intent = new Intent(action).setPackage(context.getPackageName());
        return PendingIntent.getBroadcast(context, action.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Nullable
    private PendingIntent openAppIntent() {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (intent == null) return null;
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private NotificationManager notificationManager() {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static void createChannel(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                str("morphe_fling_to_pc_channel"), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    /** The thumbnail of the video, for the background of the media controls. */
    private void loadArtwork() {
        Utils.runOnBackgroundThread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(
                        "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg").openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                Bitmap bitmap;
                try (InputStream stream = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(stream);
                } finally {
                    connection.disconnect();
                }
                if (bitmap == null) return;
                Utils.runOnMainThread(() -> {
                    if (released) return;
                    artwork = bitmap;
                    updateMetadata();
                    notificationManager().notify(NOTIFICATION_ID, buildNotification());
                });
            } catch (Exception ex) {
                Logger.printDebug(() -> "No thumbnail for the FlingPC remote: " + ex);
            }
        });
    }

    private static JSONObject extras(String name, double value) {
        JSONObject json = new JSONObject();
        try {
            json.put(name, value);
        } catch (JSONException ex) {
            throw new IllegalStateException(ex);
        }
        return json;
    }

    // endregion
}
