package io.github.xfzz7.dualpane;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Dims the screen and leaves a rounded hole over the control being explained. */
public final class GuideMask extends View {
    private final Path shade = new Path();
    private final RectF hole = new RectF();
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float radius;
    private boolean open;

    public GuideMask(Context context, AttributeSet attrs) {
        super(context, attrs);
        dim.setColor(0x99000000);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFFF5F5F7);
        float density = getResources().getDisplayMetrics().density;
        ring.setStrokeWidth(2f * density);
    }

    void setHole(RectF rect, float radiusPx) {
        open = rect != null && rect.width() > 1f && rect.height() > 1f;
        if (open) {
            hole.set(rect);
            radius = radiusPx;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        shade.reset();
        shade.addRect(0f, 0f, width, height, Path.Direction.CW);
        if (open) {
            float round = Math.min(radius, Math.min(hole.width(), hole.height()) / 2f);
            shade.addRoundRect(hole, round, round, Path.Direction.CW);
            shade.setFillType(Path.FillType.EVEN_ODD);
            canvas.drawPath(shade, dim);
            canvas.drawRoundRect(hole, round, round, ring);
            return;
        }
        canvas.drawRect(0f, 0f, width, height, dim);
    }
}
