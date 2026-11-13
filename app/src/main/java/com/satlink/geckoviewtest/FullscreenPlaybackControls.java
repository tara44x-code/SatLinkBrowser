package com.satlink.geckoviewtest;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Touch playback buttons shown over a fullscreen page video: back/forward 10 seconds,
 * play/pause and playback speed. Commands go to the page through the listener; this
 * class has no Gecko, capture or download access.
 */
final class FullscreenPlaybackControls {
    interface Listener { void onPlayerCommand(String command, double value); }

    static final double SEEK_SECONDS = 10d;
    static final double[] SPEEDS = {1.0d, 1.25d, 1.5d, 2.0d, 0.5d, 0.75d};
    private static final long HIDE_DELAY_MS = 3000L;
    static final int NARROW_WIDTH_DP = 520;

    private final Activity activity;
    private final Listener listener;
    private final FrameLayout overlay;
    private final LinearLayout bar;
    private final TextView speedButton;
    private boolean active;
    private int speedIndex;
    private final Runnable hide = this::hideControls;

    FullscreenPlaybackControls(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        overlay = new FrameLayout(activity);
        overlay.setClickable(false);

        bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.addView(button("« 10", "رجوع 10 ثواني", () -> send("seek", -SEEK_SECONDS)), buttonParams(72));
        bar.addView(button("▶ ❚❚", "تشغيل أو إيقاف مؤقت", () -> send("toggle", 0d)), buttonParams(84));
        bar.addView(button("10 »", "تقديم 10 ثواني", () -> send("seek", SEEK_SECONDS)), buttonParams(72));
        speedButton = button(speedLabel(), "سرعة التشغيل", this::nextSpeed);
        bar.addView(speedButton, buttonParams(64));

        overlay.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        ViewGroup content = activity.findViewById(android.R.id.content);
        content.addView(overlay, new ViewGroup.LayoutParams(-1, -1));
        // Portrait screens are too narrow for the buttons between the brightness and volume
        // bars, so the row moves below them.
        overlay.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            boolean narrow = (r - l) < dp(NARROW_WIDTH_DP);
            int gravity = narrow ? (Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL) : Gravity.CENTER;
            int margin = narrow ? dp(72) : 0;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) bar.getLayoutParams();
            if (params.gravity != gravity || params.bottomMargin != margin) {
                params.gravity = gravity;
                params.bottomMargin = margin;
                bar.setLayoutParams(params);
            }
        });
        overlay.setVisibility(View.GONE);
        bar.setVisibility(View.GONE);
    }

    private LinearLayout.LayoutParams buttonParams(int widthDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(widthDp), dp(56));
        params.leftMargin = dp(6);
        params.rightMargin = dp(6);
        return params;
    }

    private TextView button(String text, String description, Runnable action) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setContentDescription(description);
        view.setTextColor(Color.WHITE);
        view.setTextSize(16);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        view.setFocusable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xB3000000);
        background.setCornerRadius(dp(28));
        background.setStroke(dp(1), 0x66FFFFFF);
        view.setBackground(background);
        view.setOnClickListener(v -> {
            if (!active) return;
            action.run();
            reveal();
        });
        return view;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void send(String command, double value) {
        if (listener != null) listener.onPlayerCommand(command, value);
    }

    private void nextSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        speedButton.setText(speedLabel());
        send("rate", SPEEDS[speedIndex]);
    }

    private String speedLabel() {
        double speed = SPEEDS[speedIndex];
        String text = speed == Math.rint(speed) ? String.valueOf((int) speed)
                : String.format(Locale.US, "%.2f", speed).replaceAll("0+$", "");
        return text + "x";
    }

    void setFullscreen(boolean value) {
        if (active == value) return;
        active = value;
        overlay.removeCallbacks(hide);
        if (value) {
            // A new fullscreen video starts at the page's own speed.
            speedIndex = 0;
            speedButton.setText(speedLabel());
            overlay.setVisibility(View.VISIBLE);
            overlay.bringToFront();
            reveal();
        } else {
            bar.setVisibility(View.GONE);
            overlay.setVisibility(View.GONE);
        }
    }

    boolean isShowing() { return active && bar.getVisibility() == View.VISIBLE; }

    void reveal() {
        if (!active) return;
        overlay.removeCallbacks(hide);
        bar.setVisibility(View.VISIBLE);
        overlay.postDelayed(hide, HIDE_DELAY_MS);
    }

    private void hideControls() { bar.setVisibility(View.GONE); }

    /** Observes touches without consuming them; the page keeps receiving its own events. */
    void onScreenTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) reveal();
    }

    void onScreenKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) reveal();
    }
}
