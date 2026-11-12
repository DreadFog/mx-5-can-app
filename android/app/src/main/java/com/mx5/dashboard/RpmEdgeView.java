package com.mx5.dashboard;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

public final class RpmEdgeView extends View {
    private final Paint paint = new Paint();
    private float progress;
    private float target;
    private ValueAnimator animator;

    public RpmEdgeView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);
        setFocusable(false);
    }

    public void setProgress(float value) {
        if (value == target) return;
        target = value;
        if (animator != null) animator.cancel();
        if (value == 0) {
            progress = 0;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(progress, value);
        animator.setDuration(100);
        animator.addUpdateListener(animation -> {
            progress = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (progress <= 0) return;
        int yellow = Color.rgb(255, 220, 0);
        int orange = Color.rgb(255, 135, 0);
        int red = Color.rgb(255, 25, 10);
        int color = progress <= 0.6f
                ? blend(yellow, orange, progress / 0.6f)
                : blend(orange, red, (progress - 0.6f) / 0.4f);
        int bright = Color.argb(Math.round(255 * progress), Color.red(color), Color.green(color), Color.blue(color));
        int transparent = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color));
        float width = getWidth();
        float height = getHeight();
        float depth = Math.min(96 * getResources().getDisplayMetrics().density, Math.min(width, height) * 0.24f);
        paint.setShader(new LinearGradient(0, 0, depth, 0, bright, transparent, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, depth, height, paint);
        paint.setShader(new LinearGradient(width, 0, width - depth, 0, bright, transparent, Shader.TileMode.CLAMP));
        canvas.drawRect(width - depth, 0, width, height, paint);
        paint.setShader(new LinearGradient(0, 0, 0, depth, bright, transparent, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, depth, paint);
        paint.setShader(new LinearGradient(0, height, 0, height - depth, bright, transparent, Shader.TileMode.CLAMP));
        canvas.drawRect(0, height - depth, width, height, paint);
    }

    private static int blend(int start, int end, float fraction) {
        return Color.rgb(
                Math.round(Color.red(start) + (Color.red(end) - Color.red(start)) * fraction),
                Math.round(Color.green(start) + (Color.green(end) - Color.green(start)) * fraction),
                Math.round(Color.blue(start) + (Color.blue(end) - Color.blue(start)) * fraction));
    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
}