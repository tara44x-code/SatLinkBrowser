package com.satlink.geckoviewtest;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Window-only brightness and Android media volume. No Gecko or download access. */
final class FullscreenDisplayControls implements Application.ActivityLifecycleCallbacks {
    private final Activity activity;
    private final AudioManager audio;
    private final FrameLayout overlay;
    private final LevelBar light, sound;
    private boolean requested, active, dragging, disposed;
    private float originalBrightness;
    private final Runnable hide = this::hideControls;
    private void hideControls() {
        if (!dragging) {
            light.clearFocus(); sound.clearFocus();
            light.setVisibility(View.GONE); sound.setVisibility(View.GONE);
        }
    }

    FullscreenDisplayControls(Activity activity) {
        this.activity = activity;
        audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        overlay = new FrameLayout(activity);
        overlay.setClickable(false);
        light = new LevelBar(false); sound = new LevelBar(true);
        overlay.addView(light, params(Gravity.LEFT));
        overlay.addView(sound, params(Gravity.RIGHT));
        ViewGroup content = activity.findViewById(android.R.id.content);
        content.addView(overlay, new ViewGroup.LayoutParams(-1, -1));
        overlay.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int height = Math.max(dp(100), Math.min(dp(360), (b - t) * 55 / 100));
            for (View bar : new View[]{light, sound}) {
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) bar.getLayoutParams();
                if (p.height != height) { p.height = height; bar.setLayoutParams(p); }
            }
        });
        overlay.setVisibility(View.GONE);
        activity.getApplication().registerActivityLifecycleCallbacks(this);
    }
    private FrameLayout.LayoutParams params(int side) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(44), dp(180), side | Gravity.CENTER_VERTICAL);
        p.leftMargin = dp(6); p.rightMargin = dp(6);
        return p;
    }
    private int dp(int n) { return Math.round(n * activity.getResources().getDisplayMetrics().density); }
    void setFullscreen(boolean value) { requested = value; activate(value); }
    private void activate(boolean value) {
        if (disposed || active == value) return;
        active = value;
        overlay.removeCallbacks(hide); dragging = false;
        if (value) {
            originalBrightness = activity.getWindow().getAttributes().screenBrightness;
            overlay.setVisibility(View.VISIBLE); overlay.bringToFront(); reveal();
        } else {
            WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
            attributes.screenBrightness = originalBrightness;
            activity.getWindow().setAttributes(attributes);
            overlay.setVisibility(View.GONE);
        }
    }
    void reveal() {
        if (!active || disposed) return;
        overlay.removeCallbacks(hide);
        light.setVisibility(View.VISIBLE); sound.setVisibility(View.VISIBLE);
        light.refresh(); sound.refresh();
        if (!dragging) overlay.postDelayed(hide, 2000);
    }
    void onScreenTouch(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN || event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) reveal();
    }
    void onScreenKey(KeyEvent event) { if (event.getAction() == KeyEvent.ACTION_DOWN) reveal(); }
    private float brightness() {
        float current = activity.getWindow().getAttributes().screenBrightness;
        if (current >= 0) return current;
        return Settings.System.getInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128) / 255f;
    }
    void dispose() {
        if (disposed) return;
        activate(false); requested = false; disposed = true;
        overlay.removeCallbacks(hide);
        ((ViewGroup) overlay.getParent()).removeView(overlay);
        activity.getApplication().unregisterActivityLifecycleCallbacks(this);
    }
    @Override public void onActivityPaused(Activity a) { if (a == activity) activate(false); }
    @Override public void onActivityResumed(Activity a) { if (a == activity && requested) activate(true); }
    @Override public void onActivityDestroyed(Activity a) { if (a == activity) dispose(); }
    @Override public void onActivityCreated(Activity a, Bundle b) {}
    @Override public void onActivityStarted(Activity a) {}
    @Override public void onActivityStopped(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}

    private final class LevelBar extends View {
        private final boolean volume;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float value;
        private boolean writable = true;
        LevelBar(boolean volume) {
            super(activity); this.volume = volume;
            setFocusable(true); setClickable(true); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        void refresh() {
            if (volume) {
                writable = audio != null && !audio.isVolumeFixed();
                value = audio == null ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC) /
                        (float) Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            } else value = brightness();
            value = Math.max(0, Math.min(1, value));
            setContentDescription((volume ? "الصوت" : "سطوع الشاشة") + " " + Math.round(value * 100) + "%" +
                    (writable ? "" : " — مستوى ثابت من الجهاز"));
            invalidate();
        }
        private void change(float next) {
            if (!active || !writable) return;
            next = Math.max(volume ? 0 : 0.02f, Math.min(1, next));
            try {
                if (volume) audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                        Math.round(next * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0);
                else {
                    WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
                    attributes.screenBrightness = next;
                    activity.getWindow().setAttributes(attributes);
                }
            } catch (SecurityException ignored) { /* Device audio policy may reject adjustment. */ }
            refresh();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float x = getWidth() / 2f, start = dp(42), end = Math.max(start + 1, getHeight() - dp(30));
            paint.setColor(0x99000000); canvas.drawRoundRect(0, 0, getWidth(), getHeight(), dp(12), dp(12), paint);
            paint.setColor(writable ? Color.WHITE : Color.GRAY); paint.setTextSize(dp(18)); paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(volume ? "♫" : "☀", x, dp(26), paint);
            paint.setStrokeWidth(dp(3)); canvas.drawLine(x, start, x, end, paint);
            canvas.drawCircle(x, end - value * (end - start), dp(7), paint);
            paint.setTextSize(dp(10)); canvas.drawText(Math.round(value * 100) + "%", x, getHeight() - dp(9), paint);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!active) return false;
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                dragging = true; getParent().requestDisallowInterceptTouchEvent(true); reveal();
            }
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                float start = dp(42), end = Math.max(start + 1, getHeight() - dp(30));
                change((end - e.getY()) / (end - start)); return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                dragging = false; getParent().requestDisallowInterceptTouchEvent(false);
                if (action == MotionEvent.ACTION_UP) performClick();
                reveal(); return true;
            }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        @Override public boolean onKeyDown(int code, KeyEvent event) {
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                float step = volume && audio != null ? 1f / Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) : 0.05f;
                change(value + (code == KeyEvent.KEYCODE_DPAD_UP ? step : -step)); reveal(); return true;
            }
            return super.onKeyDown(code, event);
        }
    }
}
