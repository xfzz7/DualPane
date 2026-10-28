package io.github.xfzz7.dualpane;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Thin white arc. The platform spinner is the old Holo circle, which reads as crude on this TV. */
public final class LoadRing extends View {
    private static final float SWEEP = 108f;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Matrix spin = new Matrix();
    private SweepGradient comet;
    private ValueAnimator animator;
    private float angle;

    public LoadRing(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        float stroke = 2.4f * density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setColor(0x22F5F5F7);
        head.setStyle(Paint.Style.STROKE);
        head.setStrokeCap(Paint.Cap.ROUND);
        head.setStrokeWidth(stroke);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float cx = w / 2f;
        float cy = h / 2f;
        float tail = 1f - SWEEP / 360f;
        comet = new SweepGradient(cx, cy,
                new int[]{0x00F5F5F7, 0x00F5F5F7, 0xFFF5F5F7},
                new float[]{0f, tail, 1f});
        head.setShader(comet);
        float inset = head.getStrokeWidth() + densityPad();
        oval.set(inset, inset, w - inset, h - inset);
    }

    private float densityPad() {
        return 1.2f * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (comet == null || oval.width() <= 0f) {
            return;
        }
        canvas.drawOval(oval, track);
        spin.setRotate(angle, getWidth() / 2f, getHeight() / 2f);
        comet.setLocalMatrix(spin);
        canvas.drawArc(oval, angle - SWEEP, SWEEP, false, head);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isShown()) {
            start();
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible) {
            start();
        } else {
            stop();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private void start() {
        if (animator != null || !isAttachedToWindow()) {
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 360f);
        animator.setDuration(1100);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            angle = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }
}
