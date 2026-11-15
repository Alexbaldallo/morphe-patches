package app.morphe.extension.youtube.swipecontrols.flingtopc;

import static app.morphe.extension.shared.StringRef.str;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsConfigurationProvider;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsConfigurationProvider.SwipeZoneAction;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity;
import app.morphe.extension.youtube.swipecontrols.controller.SwipeZonesController;
import app.morphe.extension.youtube.swipecontrols.misc.Point;

/**
 * Swipe up on the player to send the video to a computer running FlingPC.
 * <p>
 * The gesture starts on the player, in portrait or in fullscreen, outside the side zones that
 * have a volume, brightness or speed action. Once the finger moves up past the touch slop the
 * gesture is taken away from the player, and a card with the current frame follows the finger.
 * Releasing it past the trigger distance, or flicking it up, throws the card off the top of the
 * screen and sends the video with its current time. Anything else puts the card back.
 */
public final class FlingToPcController {

    private enum State {
        /** Waiting for a touch on the player. */
        IDLE,
        /** A touch started on the player, but it is not yet known to be an upward swipe. */
        TRACKING,
        /** The swipe belongs to this controller and the card follows the finger. */
        CAPTURED,
        /** The card is animating after the finger was released. */
        SETTLING
    }

    /** Card scale when the swipe reaches the trigger distance. */
    private static final float CARD_SCALE_ARMED = 0.86f;

    /** Card scale when it leaves the screen. */
    private static final float CARD_SCALE_GONE = 0.7f;

    /** Darkness of the player behind the card when the swipe reaches the trigger distance. */
    private static final float DIM_ARMED = 0.6f;

    /** A swipe must be this much more vertical than horizontal. */
    private static final float VERTICAL_RATIO = 1.5f;

    private final SwipeControlsHostActivity host;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final int touchSlop;
    private final float density;
    private final int playerOverlayId;

    private State state = State.IDLE;

    /**
     * Whether the rest of the current touch gesture is consumed.
     * True from the capture until the finger is lifted, even if the card settles earlier.
     */
    private boolean swallowing;

    /** Window coordinates of the initial touch. */
    private float downX, downY;

    @Nullable
    private VelocityTracker velocityTracker;

    /** Player area in window coordinates. The gesture must start inside it. */
    private final Rect playerRect = new Rect();

    /** Area of the video picture in window coordinates. The card starts there. */
    private final Rect cardRect = new Rect();

    private WeakReference<View> playerViewRef = new WeakReference<>(null);

    private float triggerDistance;
    private boolean armed;

    /** Whether the released card is flying off the screen, as opposed to going back. */
    private boolean flying;

    /** Increases with every capture, so late frame copies of an old card are ignored. */
    private int session;

    @Nullable
    private View dimView;
    @Nullable
    private FrameLayout cardView;
    @Nullable
    private ImageView cardImage;
    @Nullable
    private TextView cardLabel;
    @Nullable
    private Bitmap cardBitmap;
    private float cornerRadius;

    public FlingToPcController(SwipeControlsHostActivity host) {
        this.host = host;
        touchSlop = ViewConfiguration.get(host).getScaledTouchSlop();
        density = host.getResources().getDisplayMetrics().density;
        playerOverlayId = ResourceUtils.getIdentifier(host, ResourceType.ID, "inset_controls_overlay_wrapper");
    }

    /**
     * @param event Touch event in window coordinates, as received by the activity.
     * @return Whether the event was consumed and must not reach the player.
     */
    public boolean onTouchEvent(MotionEvent event) {
        try {
            return handleTouchEvent(event);
        } catch (Exception ex) {
            Logger.printException(() -> "Fling to PC touch failure", ex);
            resetAll();
            return false;
        }
    }

    private boolean handleTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();

        if (state == State.CAPTURED) {
            onCaptured(event, action);
            return true;
        }

        if (swallowing) {
            // The card was put back early, the player must still not see the rest of the gesture.
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                swallowing = false;
            }
            return true;
        }

        if (state == State.TRACKING) {
            return onTracking(event, action);
        }

        if (state == State.IDLE && action == MotionEvent.ACTION_DOWN && canStartAt(event)) {
            state = State.TRACKING;
            downX = event.getX();
            downY = event.getY();
            releaseVelocityTracker();
            velocityTracker = VelocityTracker.obtain();
            velocityTracker.addMovement(event);
        }

        // The player always receives the initial touch, so taps and double taps keep working.
        return false;
    }

    // region Gesture

    private boolean canStartAt(MotionEvent event) {
        if (!Settings.FLING_TO_PC.get() || event.getPointerCount() > 1) {
            return false;
        }

        PlayerType type = PlayerType.getCurrent();
        final boolean fullscreen = type == PlayerType.WATCH_WHILE_FULLSCREEN;
        if (!fullscreen && type != PlayerType.WATCH_WHILE_MAXIMIZED) {
            return false; // Miniplayer, Shorts, feeds.
        }
        if (host.isInPictureInPictureMode() || SwipeControlsHostActivity.isPlayerLocked()) {
            return false;
        }

        View player = findPlayerView();
        if (player != null && player.isShown() && player.getWidth() > 0 && player.getHeight() > 0) {
            getRectInWindow(player, playerRect);
        } else if (fullscreen) {
            View decor = host.getWindow().getDecorView();
            playerRect.set(0, 0, decor.getWidth(), decor.getHeight());
        } else {
            return false;
        }

        if (!playerRect.contains((int) event.getX(), (int) event.getY())) {
            return false;
        }

        // Leave the side zones to the volume, brightness and speed swipes.
        SwipeControlsConfigurationProvider config = host.getConfig();
        if (config.isSwipeControlsEnabled()) {
            SwipeZonesController zones = host.getZones();
            Point point = Point.fromMotionEvent(event);
            if ((config.getLeftZoneAction() != SwipeZoneAction.OFF && zones.getLeft().contains(point))
                    || (config.getRightZoneAction() != SwipeZoneAction.OFF && zones.getRight().contains(point))) {
                return false;
            }
        }

        return true;
    }

    private boolean onTracking(MotionEvent event, int action) {
        if (action == MotionEvent.ACTION_DOWN) {
            // The previous gesture ended without an event that reached this controller.
            resetTracking();
            return handleTouchEvent(event);
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_DOWN) {
            resetTracking();
            return false;
        }
        if (action != MotionEvent.ACTION_MOVE) {
            return false;
        }

        if (velocityTracker != null) velocityTracker.addMovement(event);
        final float dx = event.getX() - downX;
        final float dy = event.getY() - downY;

        if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
            resetTracking(); // Horizontal, seek or other gestures.
            return false;
        }
        if (dy > touchSlop) {
            resetTracking(); // Downwards, minimize or exit fullscreen.
            return false;
        }
        if (-dy > touchSlop && -dy > Math.abs(dx) * VERTICAL_RATIO) {
            capture(event);
            return true;
        }

        return false;
    }

    private void capture(MotionEvent event) {
        state = State.CAPTURED;
        swallowing = true;
        armed = false;
        session++;

        // Take the gesture away from the player and from the other swipe controls.
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        host.dispatchDownstreamTouchEvent(cancel);
        host.cancelSwipeGesture(cancel);
        cancel.recycle();

        triggerDistance = clamp(playerRect.height() * 0.4f, dp(64), dp(160));
        showCard();
        updateDrag(event.getY() - downY);
        haptic(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void onCaptured(MotionEvent event, int action) {
        if (velocityTracker != null) velocityTracker.addMovement(event);
        final float dy = event.getY() - downY;

        switch (action) {
            case MotionEvent.ACTION_MOVE -> updateDrag(dy);
            case MotionEvent.ACTION_POINTER_DOWN -> settleBack();
            case MotionEvent.ACTION_CANCEL -> {
                swallowing = false;
                settleBack();
            }
            case MotionEvent.ACTION_UP -> {
                swallowing = false;
                float velocityY = 0;
                if (velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000);
                    velocityY = velocityTracker.getYVelocity();
                }
                final float up = -dy;
                final boolean flicked = velocityY < -dp(1000) && up >= triggerDistance * 0.3f;
                if (up >= triggerDistance || flicked) {
                    flyAwayAndSend(velocityY);
                } else {
                    settleBack();
                }
            }
            default -> {
            }
        }
    }

    private void updateDrag(float dy) {
        if (cardView == null) return;

        final float up = Math.max(0f, -dy);
        final float progress = Math.min(1f, up / triggerDistance);

        cardView.setY(cardRect.top - up);
        final float scale = 1f - (1f - CARD_SCALE_ARMED) * progress;
        cardView.setScaleX(scale);
        cardView.setScaleY(scale);
        setCornerRadius(dp(18) * progress);
        if (dimView != null) dimView.setAlpha(DIM_ARMED * progress);

        final boolean nowArmed = up >= triggerDistance;
        if (nowArmed != armed) {
            armed = nowArmed;
            if (cardLabel != null) {
                cardLabel.setText(str(armed ? "morphe_fling_to_pc_release" : "morphe_fling_to_pc_drag"));
            }
            if (armed) haptic(HapticFeedbackConstants.CONTEXT_CLICK);
        }
    }

    // endregion

    // region Send

    private void flyAwayAndSend(float velocityY) {
        final String videoId = VideoInformation.getVideoId();
        final String key = Settings.FLING_TO_PC_KEY.get().trim();

        if (videoId == null || videoId.isEmpty()) {
            settleBack();
            Utils.showToastShort(str("morphe_fling_to_pc_no_video"));
            return;
        }
        if (key.isEmpty()) {
            settleBack();
            Utils.showToastLong(str("morphe_fling_to_pc_no_key"));
            return;
        }

        final double seconds = Math.max(0, VideoInformation.getVideoTime()) / 1000.0;
        final boolean live = VideoInformation.isLiveStream();
        final String title = VideoInformation.getVideoTitle();

        final double videoLength = Math.max(0, VideoInformation.getVideoLength()) / 1000.0;

        FlingToPcClient.play(videoId, seconds, live, title, Settings.FLING_TO_PC_HOST.get(), key,
                (reply, failure, detail) -> {
                    if (failure == null && reply != null) {
                        final boolean pause = Settings.FLING_TO_PC_PAUSE.get();
                        if (pause) {
                            FlingToPcClient.pauseLocalPlayback(host);
                        }
                        haptic(HapticFeedbackConstants.LONG_PRESS);
                        Utils.showToastShort(str("morphe_fling_to_pc_sent", reply.name));
                        // The remote is a media session too. It starts once YouTube got the pause key,
                        // otherwise the key could reach the remote and pause the computer.
                        Utils.runOnMainThreadDelayed(() -> FlingToPcRemote.start(host, reply, key,
                                videoId, title, seconds, live ? 0 : videoLength), pause ? 400 : 0);
                    } else if (failure == FlingToPcClient.Failure.BAD_KEY) {
                        Utils.showToastLong(str("morphe_fling_to_pc_bad_key"));
                    } else if (failure == FlingToPcClient.Failure.NETWORK) {
                        Utils.showToastLong(str("morphe_fling_to_pc_network_error", detail));
                    } else {
                        Utils.showToastLong(str("morphe_fling_to_pc_not_found"));
                    }
                });

        state = State.SETTLING;
        flying = true;
        releaseVelocityTracker();

        final View card = cardView;
        if (card == null) {
            finishSettling();
            return;
        }

        // Off the top of the screen. The scale pivot is the center of the card.
        final float startY = card.getY();
        final float endY = -card.getHeight() * (1f + CARD_SCALE_GONE) / 2f - dp(16);
        final float startScale = card.getScaleX();
        final float speed = Math.max(Math.abs(velocityY), dp(1800));
        final long duration = (long) clamp((startY - endY) / speed * 1000f, 120f, 300f);

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(duration);
        // A flick keeps its speed, a slow release takes off.
        animator.setInterpolator(Math.abs(velocityY) > dp(1500)
                ? new LinearInterpolator()
                : new AccelerateInterpolator(1.5f));
        animator.addUpdateListener(animation -> {
            final float fraction = (float) animation.getAnimatedValue();
            card.setY(startY + (endY - startY) * fraction);
            final float scale = startScale + (CARD_SCALE_GONE - startScale) * fraction;
            card.setScaleX(scale);
            card.setScaleY(scale);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                card.setVisibility(View.GONE);
                View dim = dimView;
                if (dim == null) {
                    finishSettling();
                    return;
                }
                dim.animate().alpha(0f).setStartDelay(250).setDuration(300)
                        .withEndAction(FlingToPcController.this::finishSettling).start();
            }
        });
        animator.start();
    }

    private void settleBack() {
        state = State.SETTLING;
        releaseVelocityTracker();

        final View card = cardView;
        if (card == null) {
            finishSettling();
            return;
        }

        final float startY = card.getY();
        final float startScale = card.getScaleX();
        final float startCorner = cornerRadius;
        final float startDim = dimView != null ? dimView.getAlpha() : 0f;

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(220);
        animator.setInterpolator(new DecelerateInterpolator(1.5f));
        animator.addUpdateListener(animation -> {
            final float fraction = (float) animation.getAnimatedValue();
            card.setY(startY + (cardRect.top - startY) * fraction);
            final float scale = startScale + (1f - startScale) * fraction;
            card.setScaleX(scale);
            card.setScaleY(scale);
            setCornerRadius(startCorner * (1f - fraction));
            if (dimView != null) dimView.setAlpha(startDim * (1f - fraction));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // The card shows a still frame, fade it to reveal the video that kept playing.
                card.animate().alpha(0f).setDuration(90)
                        .withEndAction(FlingToPcController.this::finishSettling).start();
            }
        });
        animator.start();
    }

    private void finishSettling() {
        removeCard();
        flying = false;
        state = State.IDLE;
    }

    // endregion

    // region Card

    private void showCard() {
        removeCard();
        ViewGroup decor = (ViewGroup) host.getWindow().getDecorView();

        View surface = findVideoSurface(decor);
        if (surface != null) {
            getRectInWindow(surface, cardRect);
            if (!cardRect.intersect(playerRect)) cardRect.set(playerRect);
        } else {
            cardRect.set(playerRect);
        }
        final int width = Math.max(1, cardRect.width());
        final int height = Math.max(1, cardRect.height());

        View dim = new View(host);
        dim.setBackgroundColor(Color.BLACK);
        dim.setAlpha(0f);
        decor.addView(dim, new FrameLayout.LayoutParams(playerRect.width(), playerRect.height()));
        dim.setX(playerRect.left);
        dim.setY(playerRect.top);
        dimView = dim;

        FrameLayout card = new FrameLayout(host);
        card.setBackgroundColor(Color.BLACK);
        card.setAlpha(0f);
        card.setElevation(dp(16));
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
            }
        });
        card.setClipToOutline(true);

        ImageView image = new ImageView(host);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        card.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView label = new TextView(host);
        label.setText(str("morphe_fling_to_pc_drag"));
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xB3000000);
        pill.setCornerRadius(dp(100));
        label.setBackground(pill);
        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        labelParams.bottomMargin = dp(16);
        card.addView(label, labelParams);

        decor.addView(card, new FrameLayout.LayoutParams(width, height));
        card.setX(cardRect.left);
        card.setY(cardRect.top);

        cardView = card;
        cardImage = image;
        cardLabel = label;
        cornerRadius = 0;

        captureFrame(surface, width, height);
    }

    /**
     * Copies the current video frame into the card. A SurfaceView is not part of the window
     * pixels, so it has to be copied on its own.
     */
    private void captureFrame(@Nullable View surface, int width, int height) {
        final int captureSession = session;
        // Half resolution is enough for a card that only lives for a few hundred milliseconds.
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, width / 2), Math.max(1, height / 2), Bitmap.Config.ARGB_8888);
        cardBitmap = bitmap;

        try {
            if (surface instanceof TextureView textureView) {
                revealCard(captureSession, textureView.getBitmap(bitmap) != null);
            } else if (surface instanceof SurfaceView surfaceView) {
                PixelCopy.request(surfaceView, bitmap,
                        result -> revealCard(captureSession, result == PixelCopy.SUCCESS),
                        mainHandler);
            } else {
                revealCard(captureSession, false);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Fling to PC could not copy the video frame: " + ex);
            revealCard(captureSession, false);
        }

        // Show the card without the frame if copying it is slow.
        mainHandler.postDelayed(() -> revealCard(captureSession, false), 120);
    }

    private void revealCard(int forSession, boolean hasFrame) {
        if (forSession != session || cardView == null) return;
        if (hasFrame && cardImage != null && cardBitmap != null && !cardBitmap.isRecycled()) {
            cardImage.setImageBitmap(cardBitmap);
        }
        // Not while the card goes back, its fade out runs on the same property animator.
        if (cardView.getAlpha() < 1f && cardView.getVisibility() == View.VISIBLE
                && (state == State.CAPTURED || flying)) {
            cardView.animate().alpha(1f).setDuration(70).start();
        }
    }

    private void removeCard() {
        ViewGroup decor = (ViewGroup) host.getWindow().getDecorView();
        if (cardView != null) {
            cardView.animate().cancel();
            decor.removeView(cardView);
        }
        if (dimView != null) {
            dimView.animate().cancel();
            decor.removeView(dimView);
        }
        if (cardImage != null) {
            cardImage.setImageDrawable(null);
        }
        if (cardBitmap != null) {
            cardBitmap.recycle();
        }
        cardView = null;
        dimView = null;
        cardImage = null;
        cardLabel = null;
        cardBitmap = null;
    }

    private void setCornerRadius(float radius) {
        cornerRadius = radius;
        if (cardView != null) cardView.invalidateOutline();
    }

    // endregion

    // region Helpers

    @Nullable
    private View findPlayerView() {
        View player = playerViewRef.get();
        if (player == null || !player.isAttachedToWindow()) {
            player = playerOverlayId != 0 ? host.findViewById(playerOverlayId) : null;
            playerViewRef = new WeakReference<>(player);
        }
        return player;
    }

    /**
     * The video surface is the largest visible SurfaceView or TextureView on the player.
     */
    @Nullable
    private View findVideoSurface(View root) {
        View best = null;
        long bestArea = 0;
        Rect rect = new Rect();
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.push(root);

        while (!pending.isEmpty()) {
            View view = pending.pop();
            if (view.getVisibility() != View.VISIBLE) continue;

            if (view instanceof SurfaceView || view instanceof TextureView) {
                if (view.getWidth() > 0 && view.getHeight() > 0) {
                    getRectInWindow(view, rect);
                    if (rect.intersect(playerRect)) {
                        final long area = (long) rect.width() * rect.height();
                        if (area > bestArea) {
                            bestArea = area;
                            best = view;
                        }
                    }
                }
                continue;
            }

            if (view instanceof ViewGroup group) {
                for (int i = 0, count = group.getChildCount(); i < count; i++) {
                    pending.push(group.getChildAt(i));
                }
            }
        }

        // Ignore small surfaces such as seekbar previews.
        final long playerArea = (long) playerRect.width() * playerRect.height();
        return bestArea * 4 >= playerArea ? best : null;
    }

    private static void getRectInWindow(View view, Rect out) {
        int[] location = new int[2];
        view.getLocationInWindow(location);
        out.set(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    private void haptic(int feedbackConstant) {
        if (host.getConfig().shouldEnableHapticFeedback()) {
            host.getWindow().getDecorView().performHapticFeedback(feedbackConstant);
        }
    }

    private void resetTracking() {
        state = State.IDLE;
        releaseVelocityTracker();
    }

    private void resetAll() {
        swallowing = false;
        flying = false;
        resetTracking();
        removeCard();
    }

    private void releaseVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // endregion
}
