package app.morphe.extension.youtube.swipecontrols.flingtopc;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

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
 * Remote for the video playing on the computer, shown inside the app.
 * <p>
 * A panel rises from the bottom of the screen once the computer accepts the video: play and
 * pause, the seek bar, 10 seconds back and forward, the volume of the computer, continue on the
 * phone and remove from the PC. While the panel is open the volume keys of the phone change the
 * volume of the computer. Dragging the panel down, the back button or a touch outside shrink it
 * to a small chip that floats over the app and opens the panel again. The state is read from the
 * computer every few seconds, so the panel follows what happens there.
 */
public final class FlingToPcRemote {

    private static final double SKIP_SECONDS = 10;
    private static final long POLL_INTERVAL_MS = 2500;
    private static final long TICK_INTERVAL_MS = 500;
    /** About 10 seconds without answers. */
    private static final int MAX_FAILED_POLLS = 4;
    private static final int VOLUME_KEY_STEP = 5;
    /** The state of the computer does not move a control the user has just moved. */
    private static final long LOCAL_CHANGE_HOLD_MS = 1500;
    private static final int SEEK_BAR_MAX = 10000;
    private static final float SCRIM_ALPHA = 0.55f;

    private static final int COLOR_PANEL = 0xFF1C1C1E;
    private static final int COLOR_CONTROL = 0xFF343437;
    private static final int COLOR_TEXT = Color.WHITE;
    private static final int COLOR_TEXT_SECONDARY = 0xFFAEAEB2;
    private static final int COLOR_ACCENT = 0xFFFF4E45;
    private static final int COLOR_REMOVE = 0xFFD93025;

    @Nullable
    private static FlingToPcRemote current;

    /** Whether the touch gesture in progress started on the chip. */
    private static boolean chipTouch;

    /** Where the user left the chip, kept for the next time it shows. */
    private static float chipTranslationX, chipTranslationY;

    private final Activity activity;
    private final Context themed;
    private final float density;
    private final InetSocketAddress computer;
    private final String computerName;
    private final String key;
    private final String videoId;
    private final String title;
    private final boolean live;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean released;
    private boolean playing = true;
    private boolean buffering = true;
    /** Seconds, at {@link #positionAt}. */
    private double position;
    private long positionAt;
    private double duration;
    /** 0 to 100. */
    private int volume = 100;
    private long volumeChangedAt;
    private long seekedAt;
    private boolean volumeSendPending;
    private int failedPolls;
    @Nullable
    private Bitmap artwork;

    // Panel. All null while it is not showing.
    @Nullable
    private Dialog dialog;
    private SheetLayout sheet;
    private View scrim;
    private LinearLayout panel;
    private ImageView thumbnailView;
    private ImageView playButton;
    private SeekBar seekBar;
    private TextView positionText;
    private TextView durationText;
    private SeekBar volumeBar;
    private TextView volumeText;
    private boolean seekBarTracking;
    private boolean minimizing;

    @Nullable
    private View chip;

    private final Runnable pollRunnable = this::poll;
    private final Runnable tickRunnable = this::tick;
    private final Runnable volumeSendRunnable = () -> {
        volumeSendPending = false;
        send("volume", extras("level", volume));
    };

    /**
     * Opens the remote for a video the computer accepted. Replaces the previous remote.
     *
     * @param seconds  Time the video was sent at.
     * @param duration Length of the video in seconds, or 0 if not known.
     */
    public static void start(Activity activity, FlingToPcClient.Reply reply, String key, String videoId,
                             String title, double seconds, double duration, boolean live) {
        try {
            stopCurrent();
            current = new FlingToPcRemote(activity, reply, key, videoId, title, seconds, duration, live);
            current.begin();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show the FlingPC remote", ex);
            if (current != null) current.release();
        }
    }

    public static void stopCurrent() {
        if (current != null) current.release();
    }

    /**
     * @param event Touch event in window coordinates, as received by the activity.
     * @return Whether the event belongs to a gesture on the floating chip. Such a gesture
     *         must not start a swipe on the player that is under the chip.
     */
    public static boolean ownsTouch(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            FlingToPcRemote remote = current;
            chipTouch = remote != null && remote.isOnChip(event);
        }
        final boolean owns = chipTouch;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            chipTouch = false;
        }
        return owns;
    }

    private FlingToPcRemote(Activity activity, FlingToPcClient.Reply reply, String key, String videoId,
                            String title, double seconds, double duration, boolean live) {
        this.activity = activity;
        // The app theme can be light. The panel is always dark, like the player.
        this.themed = new ContextThemeWrapper(activity, android.R.style.Theme_DeviceDefault);
        this.density = activity.getResources().getDisplayMetrics().density;
        this.computer = reply.address;
        this.computerName = reply.name;
        this.key = key;
        this.videoId = videoId;
        this.title = title == null || title.isEmpty() ? "YouTube" : title;
        this.live = live;
        this.position = seconds;
        this.positionAt = SystemClock.elapsedRealtime();
        this.duration = live ? 0 : duration;
    }

    private void begin() {
        showPanel();
        loadArtwork();
        handler.postDelayed(pollRunnable, 800);
    }

    private boolean isActivityGone() {
        return activity.isFinishing() || activity.isDestroyed();
    }

    // region Commands

    private void togglePlaying() {
        position = currentPosition();
        positionAt = SystemClock.elapsedRealtime();
        playing = !playing;
        send(playing ? "resume" : "pause", null);
        updatePanel();
    }

    private void seekTo(double seconds) {
        double target = Math.max(0, seconds);
        if (duration > 0) target = Math.min(target, Math.max(0, duration - 1));
        position = target;
        positionAt = seekedAt = SystemClock.elapsedRealtime();
        send("seek", extras("t", target));
        updatePanel();
    }

    /**
     * @param finished False while the user is still dragging the volume bar.
     */
    private void setVolume(int level, boolean finished) {
        volume = Math.max(0, Math.min(100, level));
        volumeChangedAt = SystemClock.elapsedRealtime();
        if (finished) {
            handler.removeCallbacks(volumeSendRunnable);
            volumeSendPending = false;
            send("volume", extras("level", volume));
        } else if (!volumeSendPending) {
            // A drag changes the value many times a second.
            volumeSendPending = true;
            handler.postDelayed(volumeSendRunnable, 120);
        }
        updateVolumeViews();
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
            if (!live) VideoInformation.seekTo((long) (seconds * 1000));
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not seek on the phone", ex);
        }
        FlingToPcClient.playLocalPlayback(activity);
    }

    private void send(String command, @Nullable JSONObject extras) {
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
        if (isActivityGone()) {
            release();
            return;
        }
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
        final long now = SystemClock.elapsedRealtime();

        playing = state.optBoolean("playing", playing);
        buffering = state.optBoolean("buffering", false);
        if (now - seekedAt > LOCAL_CHANGE_HOLD_MS) {
            position = state.optDouble("t", position);
            positionAt = now;
        }
        if (!live) {
            final double newDuration = state.optDouble("duration", 0);
            if (newDuration > 0) duration = newDuration;
        }
        if (now - volumeChangedAt > LOCAL_CHANGE_HOLD_MS) {
            volume = state.optBoolean("muted") ? 0 : state.optInt("volume", volume);
        }

        updatePanel();
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
        if (current == this) current = null;
        removeChip();

        final Dialog showing = dialog;
        dialog = null;
        if (showing == null) return;
        if (panel != null && panel.isAttachedToWindow() && panel.getHeight() > 0) {
            scrim.animate().alpha(0f).setDuration(180).start();
            panel.animate().translationY(panel.getHeight()).setDuration(180)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> dismiss(showing)).start();
        } else {
            dismiss(showing);
        }
    }

    private static void dismiss(Dialog dialog) {
        try {
            dialog.dismiss();
        } catch (Exception ex) {
            // The activity is gone.
            Logger.printDebug(() -> "FlingPC panel was already gone: " + ex);
        }
    }

    // endregion

    // region Panel

    private void showPanel() {
        if (released || dialog != null || isActivityGone()) return;
        removeChip();
        minimizing = false;

        Dialog newDialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar) {
            @Override
            public boolean onKeyDown(int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    setVolume(volume + (keyCode == KeyEvent.KEYCODE_VOLUME_UP ? VOLUME_KEY_STEP : -VOLUME_KEY_STEP), true);
                    return true;
                }
                return super.onKeyDown(keyCode, event);
            }

            @Override
            public boolean onKeyUp(int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    return true;
                }
                return super.onKeyUp(keyCode, event);
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onBackPressed() {
                minimize();
            }
        };

        sheet = new SheetLayout(themed);
        buildPanel();
        newDialog.setContentView(sheet, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Window window = newDialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setWindowAnimations(0);
            // The panel draws under the navigation bar and adds its height as padding.
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            setSystemBarsTransparent(window);
        }

        dialog = newDialog;
        updatePanel();
        newDialog.show();

        // Rise from the bottom once the height of the panel is known.
        scrim.setAlpha(0f);
        sheet.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                sheet.getViewTreeObserver().removeOnPreDrawListener(this);
                panel.setTranslationY(panel.getHeight());
                panel.animate().translationY(0f).setDuration(280)
                        .setInterpolator(new DecelerateInterpolator(2f)).start();
                scrim.animate().alpha(SCRIM_ALPHA).setDuration(280).start();
                return true;
            }
        });

        handler.removeCallbacks(tickRunnable);
        handler.postDelayed(tickRunnable, TICK_INTERVAL_MS);
    }

    @SuppressWarnings("deprecation")
    private static void setSystemBarsTransparent(Window window) {
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }
    }

    /**
     * Shrinks the panel to the floating chip. The video keeps playing on the computer.
     */
    private void minimize() {
        final Dialog showing = dialog;
        if (showing == null || minimizing || released) return;
        minimizing = true;
        handler.removeCallbacks(tickRunnable);

        scrim.animate().alpha(0f).setDuration(200).start();
        panel.animate().translationY(panel.getHeight()).setDuration(200)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    minimizing = false;
                    if (dialog != showing) return; // Released meanwhile.
                    dialog = null;
                    dismiss(showing);
                    showChip();
                }).start();
    }

    private void tick() {
        if (released || dialog == null) return;
        updateProgressViews();
        handler.postDelayed(tickRunnable, TICK_INTERVAL_MS);
    }

    private void updatePanel() {
        if (dialog == null || released) return;
        playButton.setImageDrawable(new IconDrawable(
                playing ? IconDrawable.PAUSE : IconDrawable.PLAY, COLOR_PANEL));
        if (artwork != null) thumbnailView.setImageBitmap(artwork);
        updateProgressViews();
        updateVolumeViews();
    }

    private void updateProgressViews() {
        if (dialog == null) return;
        final boolean seekable = !live && duration > 0;
        seekBar.setVisibility(seekable ? View.VISIBLE : View.GONE);
        durationText.setVisibility(seekable ? View.VISIBLE : View.INVISIBLE);
        if (live) {
            positionText.setText(str("morphe_fling_to_pc_live"));
            return;
        }
        final double seconds = currentPosition();
        if (!seekBarTracking) {
            positionText.setText(formatTime(seconds));
            if (seekable) seekBar.setProgress((int) Math.round(seconds / duration * SEEK_BAR_MAX));
        }
        if (seekable) durationText.setText(formatTime(duration));
    }

    private void updateVolumeViews() {
        if (dialog == null) return;
        if (volumeBar.getProgress() != volume) volumeBar.setProgress(volume);
        volumeText.setText(String.valueOf(volume));
    }

    private void buildPanel() {
        scrim = new View(themed);
        scrim.setBackgroundColor(Color.BLACK);
        scrim.setOnClickListener(v -> minimize());
        sheet.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(themed);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true); // Touches on the empty parts do not reach the scrim.
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(COLOR_PANEL);
        final float radius = dp(22);
        panelBackground.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        panel.setBackground(panelBackground);
        panel.setElevation(dp(16));
        final int side = dp(20);
        panel.setPadding(side, dp(8), side, dp(16));
        sheet.setOnApplyWindowInsetsListener((v, insets) -> {
            panel.setPadding(side, dp(8), side, dp(16) + bottomInset(insets));
            return insets;
        });
        sheet.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        // Handle.
        View handle = new View(themed);
        handle.setBackground(roundRect(0x55FFFFFF, dp(2)));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(12);
        panel.addView(handle, handleParams);

        // Thumbnail, title and computer.
        LinearLayout header = new LinearLayout(themed);
        header.setGravity(Gravity.CENTER_VERTICAL);
        thumbnailView = new ImageView(themed);
        thumbnailView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnailView.setBackground(roundRect(COLOR_CONTROL, dp(8)));
        thumbnailView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(8));
            }
        });
        thumbnailView.setClipToOutline(true);
        LinearLayout.LayoutParams thumbnailParams = new LinearLayout.LayoutParams(dp(96), dp(54));
        thumbnailParams.rightMargin = dp(12);
        header.addView(thumbnailView, thumbnailParams);

        LinearLayout texts = new LinearLayout(themed);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(title, 15, COLOR_TEXT, true);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(titleView);
        TextView computerView = text(str("morphe_fling_to_pc_sent", computerName), 13, COLOR_TEXT_SECONDARY, false);
        computerView.setSingleLine(true);
        computerView.setEllipsize(TextUtils.TruncateAt.END);
        computerView.setPadding(0, dp(2), 0, 0);
        texts.addView(computerView);
        header.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView minimizeButton = iconButton(IconDrawable.CHEVRON_DOWN, COLOR_TEXT, 0, dp(40), dp(9),
                "morphe_fling_to_pc_minimize");
        minimizeButton.setOnClickListener(v -> minimize());
        header.addView(minimizeButton);
        panel.addView(header, rowParams(dp(10)));

        // Seek bar and times.
        seekBar = new SeekBar(themed);
        seekBar.setMax(SEEK_BAR_MAX);
        tint(seekBar);
        seekBar.setPadding(dp(8), dp(6), dp(8), dp(6));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) positionText.setText(formatTime(duration * progress / SEEK_BAR_MAX));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                seekBarTracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                seekBarTracking = false;
                seekTo(duration * bar.getProgress() / SEEK_BAR_MAX);
            }
        });
        LinearLayout.LayoutParams seekParams = rowParams(0);
        seekParams.leftMargin = seekParams.rightMargin = -dp(8); // The bar starts at the edge of the texts.
        panel.addView(seekBar, seekParams);

        LinearLayout times = new LinearLayout(themed);
        positionText = text("", 12, COLOR_TEXT_SECONDARY, false);
        durationText = text("", 12, COLOR_TEXT_SECONDARY, false);
        durationText.setGravity(Gravity.END);
        times.addView(positionText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(durationText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(times, rowParams(dp(6)));

        // Back 10 s, play or pause, forward 10 s.
        LinearLayout controls = new LinearLayout(themed);
        controls.setGravity(Gravity.CENTER);
        TextView rewind = skipButton("−10", "morphe_fling_to_pc_rewind");
        rewind.setOnClickListener(v -> seekTo(currentPosition() - SKIP_SECONDS));
        TextView forward = skipButton("+10", "morphe_fling_to_pc_forward");
        forward.setOnClickListener(v -> seekTo(currentPosition() + SKIP_SECONDS));
        playButton = iconButton(IconDrawable.PAUSE, COLOR_PANEL, Color.WHITE, dp(64), dp(18),
                "morphe_fling_to_pc_play_pause");
        playButton.setOnClickListener(v -> togglePlaying());
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(64), dp(64));
        playParams.leftMargin = playParams.rightMargin = dp(28);
        controls.addView(rewind, new LinearLayout.LayoutParams(dp(52), dp(52)));
        controls.addView(playButton, playParams);
        controls.addView(forward, new LinearLayout.LayoutParams(dp(52), dp(52)));
        if (live) {
            rewind.setVisibility(View.INVISIBLE);
            forward.setVisibility(View.INVISIBLE);
        }
        panel.addView(controls, rowParams(dp(8)));

        // Volume of the computer.
        LinearLayout volumeRow = new LinearLayout(themed);
        volumeRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView speaker = new ImageView(themed);
        speaker.setImageDrawable(new IconDrawable(IconDrawable.SPEAKER, COLOR_TEXT_SECONDARY));
        speaker.setContentDescription(str("morphe_fling_to_pc_volume"));
        volumeRow.addView(speaker, new LinearLayout.LayoutParams(dp(24), dp(24)));
        volumeBar = new SeekBar(themed);
        volumeBar.setMax(100);
        tint(volumeBar);
        volumeBar.setPadding(dp(12), dp(6), dp(12), dp(6));
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) setVolume(progress, false);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                setVolume(bar.getProgress(), true);
            }
        });
        volumeRow.addView(volumeBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        volumeText = text("", 13, COLOR_TEXT_SECONDARY, false);
        volumeText.setGravity(Gravity.END);
        volumeRow.addView(volumeText, new LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(volumeRow, rowParams(dp(14)));

        // Continue on the phone, remove from the PC.
        LinearLayout buttons = new LinearLayout(themed);
        TextView backButton = pillButton(str("morphe_fling_to_pc_back"), COLOR_CONTROL);
        backButton.setOnClickListener(v -> continueOnPhone());
        TextView closeButton = pillButton(str("morphe_fling_to_pc_close"), COLOR_REMOVE);
        closeButton.setOnClickListener(v -> closeOnComputer());
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        backParams.rightMargin = dp(10);
        buttons.addView(backButton, backParams);
        buttons.addView(closeButton, new LinearLayout.LayoutParams(0, dp(44), 1f));
        panel.addView(buttons, rowParams(0));

        sheet.applyConfiguration(activity.getResources().getConfiguration());
    }

    @SuppressWarnings("deprecation")
    private static int bottomInset(android.view.WindowInsets insets) {
        return insets.getSystemWindowInsetBottom();
    }

    /**
     * Root of the panel window. Dragging the panel down shrinks it to the chip.
     */
    private final class SheetLayout extends FrameLayout {
        private final int touchSlop;
        private float downY;
        private boolean startedOnPanel;
        private boolean dragging;

        SheetLayout(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        /**
         * In landscape the panel does not span the screen, and on a low screen it drops the
         * thumbnail so everything fits.
         */
        void applyConfiguration(Configuration configuration) {
            if (panel == null) return;
            ViewGroup.LayoutParams params = panel.getLayoutParams();
            params.width = configuration.screenWidthDp > 520 ? dp(480) : ViewGroup.LayoutParams.MATCH_PARENT;
            panel.setLayoutParams(params);
            thumbnailView.setVisibility(configuration.screenHeightDp < 400 ? View.GONE : View.VISIBLE);
        }

        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            applyConfiguration(newConfig);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    downY = event.getRawY();
                    dragging = false;
                    startedOnPanel = event.getY() >= panel.getTop() + panel.getTranslationY();
                }
                case MotionEvent.ACTION_MOVE -> {
                    // A bar being dragged asks its parents not to intercept, so this is never called for it.
                    if (startedOnPanel && !minimizing && event.getRawY() - downY > touchSlop) {
                        dragging = true;
                        downY = event.getRawY();
                        panel.animate().cancel();
                        scrim.animate().cancel();
                        return true;
                    }
                }
                default -> {
                }
            }
            return false;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!dragging) return super.onTouchEvent(event);

            final float height = Math.max(1, panel.getHeight());
            final float dy = Math.max(0f, event.getRawY() - downY);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE -> {
                    panel.setTranslationY(dy);
                    scrim.setAlpha(SCRIM_ALPHA * (1f - Math.min(1f, dy / height)));
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false;
                    if (event.getActionMasked() == MotionEvent.ACTION_UP && dy > Math.min(height * 0.25f, dp(96))) {
                        minimize();
                    } else {
                        panel.animate().translationY(0f).setDuration(180).start();
                        scrim.animate().alpha(SCRIM_ALPHA).setDuration(180).start();
                    }
                }
                default -> {
                }
            }
            return true;
        }
    }

    // endregion

    // region Chip

    /**
     * A small floating button over the app, shown while the panel is minimized.
     */
    @SuppressLint("ClickableViewAccessibility")
    private void showChip() {
        if (released || chip != null || isActivityGone()) return;
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();

        LinearLayout view = new LinearLayout(themed);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(12), dp(9), dp(14), dp(9));
        GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_PANEL);
        background.setCornerRadius(dp(100));
        background.setStroke(dp(1), 0x40FFFFFF);
        view.setBackground(background);
        view.setElevation(dp(8));

        ImageView icon = new ImageView(themed);
        icon.setImageDrawable(new IconDrawable(IconDrawable.MONITOR, COLOR_ACCENT));
        view.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView name = text(computerName, 13, COLOR_TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMaxWidth(dp(120));
        name.setPadding(dp(6), 0, 0, 0);
        view.addView(name);
        view.setContentDescription(str("morphe_fling_to_pc_sent", computerName));

        view.setOnClickListener(v -> showPanel());
        final int touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        view.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY, startX, startY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> {
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = v.getTranslationX();
                        startY = v.getTranslationY();
                        moved = false;
                    }
                    case MotionEvent.ACTION_MOVE -> {
                        final float dx = event.getRawX() - downX;
                        final float dy = event.getRawY() - downY;
                        if (!moved && Math.hypot(dx, dy) > touchSlop) moved = true;
                        if (moved) moveChip(v, startX + dx, startY + dy);
                    }
                    case MotionEvent.ACTION_UP -> {
                        if (!moved) v.performClick();
                    }
                    default -> {
                    }
                }
                return true;
            }
        });

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        params.rightMargin = dp(12);
        params.bottomMargin = dp(150);
        decor.addView(view, params);
        chip = view;

        // Where the user left it, once its size is known.
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                moveChip(v, chipTranslationX, chipTranslationY));
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(150).start();
    }

    /** Moves the chip, keeping it inside the window. */
    private static void moveChip(View view, float translationX, float translationY) {
        if (!(view.getParent() instanceof View parent) || view.getWidth() == 0) return;
        chipTranslationX = Math.max(-view.getLeft(), Math.min(parent.getWidth() - view.getRight(), translationX));
        chipTranslationY = Math.max(-view.getTop(), Math.min(parent.getHeight() - view.getBottom(), translationY));
        view.setTranslationX(chipTranslationX);
        view.setTranslationY(chipTranslationY);
    }

    private void removeChip() {
        final View view = chip;
        chip = null;
        if (view == null) return;
        view.animate().cancel();
        if (view.getParent() instanceof ViewGroup parent) {
            parent.removeView(view);
        }
    }

    private boolean isOnChip(MotionEvent event) {
        final View view = chip;
        if (view == null || !view.isAttachedToWindow()) return false;
        int[] location = new int[2];
        view.getLocationInWindow(location);
        return event.getX() >= location[0] && event.getX() < location[0] + view.getWidth()
                && event.getY() >= location[1] && event.getY() < location[1] + view.getHeight();
    }

    // endregion

    // region Views

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(themed);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView skipButton(String label, String descriptionKey) {
        TextView view = text(label, 15, COLOR_TEXT, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(ripple(roundRect(COLOR_CONTROL, dp(100))));
        view.setContentDescription(str(descriptionKey));
        return view;
    }

    private TextView pillButton(String label, int color) {
        TextView view = text(label, 14, COLOR_TEXT, true);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setBackground(ripple(roundRect(color, dp(100))));
        return view;
    }

    /**
     * @param background Color of the round background, or 0 for none.
     */
    private ImageView iconButton(int icon, int iconColor, int background, int size, int padding,
                                 String descriptionKey) {
        ImageView view = new ImageView(themed);
        view.setImageDrawable(new IconDrawable(icon, iconColor));
        view.setPadding(padding, padding, padding, padding);
        view.setBackground(ripple(roundRect(background, dp(100))));
        view.setContentDescription(str(descriptionKey));
        view.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return view;
    }

    private static void tint(SeekBar bar) {
        bar.setProgressTintList(ColorStateList.valueOf(COLOR_ACCENT));
        bar.setThumbTintList(ColorStateList.valueOf(COLOR_ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x66FFFFFF));
    }

    private static GradientDrawable roundRect(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static Drawable ripple(GradientDrawable shape) {
        return new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), shape, shape);
    }

    private LinearLayout.LayoutParams rowParams(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = bottomMargin;
        return params;
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private static String formatTime(double seconds) {
        final long total = Math.max(0, Math.round(seconds));
        final long hours = total / 3600;
        final long minutes = total % 3600 / 60;
        final long secs = total % 60;
        return hours > 0
                ? hours + ":" + twoDigits(minutes) + ":" + twoDigits(secs)
                : minutes + ":" + twoDigits(secs);
    }

    private static String twoDigits(long value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    /** The thumbnail of the video. */
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
                    updatePanel();
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

    /**
     * Icons drawn on a 24 x 24 grid, so the patch does not have to add image resources.
     */
    private static final class IconDrawable extends Drawable {
        static final int PLAY = 0;
        static final int PAUSE = 1;
        static final int CHEVRON_DOWN = 2;
        static final int SPEAKER = 3;
        static final int MONITOR = 4;

        private final int icon;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();

        IconDrawable(int icon, int color) {
            this.icon = icon;
            fill.setColor(color);
            fill.setStyle(Paint.Style.FILL);
            stroke.setColor(color);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2f);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            final float scale = Math.min(bounds.width(), bounds.height()) / 24f;
            canvas.save();
            canvas.translate(bounds.left + (bounds.width() - 24 * scale) / 2f,
                    bounds.top + (bounds.height() - 24 * scale) / 2f);
            canvas.scale(scale, scale);
            path.rewind();

            switch (icon) {
                case PLAY -> {
                    path.moveTo(8.5f, 5f);
                    path.lineTo(19.5f, 12f);
                    path.lineTo(8.5f, 19f);
                    path.close();
                    canvas.drawPath(path, fill);
                }
                case PAUSE -> {
                    canvas.drawRoundRect(6.5f, 5f, 10.5f, 19f, 1f, 1f, fill);
                    canvas.drawRoundRect(13.5f, 5f, 17.5f, 19f, 1f, 1f, fill);
                }
                case CHEVRON_DOWN -> {
                    path.moveTo(6f, 9f);
                    path.lineTo(12f, 15f);
                    path.lineTo(18f, 9f);
                    canvas.drawPath(path, stroke);
                }
                case SPEAKER -> {
                    path.moveTo(3f, 9.5f);
                    path.lineTo(7f, 9.5f);
                    path.lineTo(12f, 5.5f);
                    path.lineTo(12f, 18.5f);
                    path.lineTo(7f, 14.5f);
                    path.lineTo(3f, 14.5f);
                    path.close();
                    canvas.drawPath(path, fill);
                    rect.set(10.5f, 8f, 16.5f, 16f);
                    canvas.drawArc(rect, -55f, 110f, false, stroke);
                    rect.set(8.5f, 4.5f, 20.5f, 19.5f);
                    canvas.drawArc(rect, -50f, 100f, false, stroke);
                }
                case MONITOR -> {
                    canvas.drawRoundRect(3f, 4.5f, 21f, 16f, 2f, 2f, stroke);
                    canvas.drawLine(12f, 16f, 12f, 19.5f, stroke);
                    canvas.drawLine(8f, 19.5f, 16f, 19.5f, stroke);
                }
                default -> {
                }
            }
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            stroke.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            fill.setColorFilter(colorFilter);
            stroke.setColorFilter(colorFilter);
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // endregion
}
