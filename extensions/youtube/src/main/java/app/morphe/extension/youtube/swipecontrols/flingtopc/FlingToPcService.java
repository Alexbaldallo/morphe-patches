package app.morphe.extension.youtube.swipecontrols.flingtopc;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;

/**
 * Keeps YouTube running while a video plays on the computer.
 * <p>
 * Without it Android freezes the app in the background a few seconds after the phone video is
 * paused, and the buttons of the remote would do nothing until YouTube is opened again.
 * Declared in the manifest by the swipe controls patch.
 */
@SuppressWarnings("unused")
public final class FlingToPcService extends Service {

    @Nullable
    private static FlingToPcService instance;

    /** The remote ended before the service started. */
    private static boolean stopRequested;

    static void start(Context context) {
        stopRequested = false;
        try {
            context.startForegroundService(new Intent(context, FlingToPcService.class));
        } catch (Exception ex) {
            // The notification still works, but the app can be frozen in the background.
            Logger.printInfo(() -> "Could not start the FlingPC service", ex);
        }
    }

    static void stop() {
        if (instance != null) {
            instance.stopForegroundAndSelf();
        } else {
            stopRequested = true;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android requires startForeground after startForegroundService, even if the remote already ended.
        FlingToPcRemote remote = FlingToPcRemote.getCurrent();
        Notification notification = remote != null
                ? remote.buildNotification()
                : FlingToPcRemote.buildPlaceholderNotification(this);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(FlingToPcRemote.NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(FlingToPcRemote.NOTIFICATION_ID, notification);
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "FlingPC service could not go to the foreground", ex);
        }

        if (remote == null || stopRequested) {
            stopForegroundAndSelf();
        }
        return START_NOT_STICKY;
    }

    private void stopForegroundAndSelf() {
        stopRequested = false;
        // The remote cancels its notification itself.
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
