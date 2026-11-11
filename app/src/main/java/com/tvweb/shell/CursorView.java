package com.tvweb.shell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * 虚拟鼠标光标。绘制在 WebView 之上，本身不消费触摸事件，
 * 只负责显示位置；真正的点击由 MainActivity 转换坐标后派发。
 */
public class CursorView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripple = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();

    private float cx = -1f;
    private float cy = -1f;
    private final float s;
    private long pressAt = 0L;

    public CursorView(Context context) {
        super(context);
        s = context.getResources().getDisplayMetrics().density * 1.6f;

        fill.setColor(Color.WHITE);
        fill.setStyle(Paint.Style.FILL);

        edge.setColor(0xCC000000);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(2f, s * 0.9f));

        ripple.setColor(0x88FFFFFF);
        ripple.setStyle(Paint.Style.STROKE);
        ripple.setStrokeWidth(Math.max(1f, s * 0.7f));

        setClickable(false);
        setFocusable(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (cx < 0 && w > 0 && h > 0) {
            cx = w / 2f;
            cy = h / 2f;
        }
    }

    /** 相对移动，超出屏幕时贴边（贴边后由 MainActivity 触发翻页滚动）。 */
    public void moveBy(float dx, float dy) {
        int w = getWidth();
        int h = getHeight();
        cx = Math.max(4f, Math.min(w - 4f, cx + dx));
        cy = Math.max(4f, Math.min(h - 4f, cy + dy));
        invalidate();
    }

    public void placeAt(float x, float y) {
        cx = x;
        cy = y;
        invalidate();
    }

    public float cx() {
        return cx;
    }

    public float cy() {
        return cy;
    }

    public void pulse() {
        pressAt = System.currentTimeMillis();
        invalidate();
        postInvalidateDelayed(120);
        postInvalidateDelayed(240);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (cx < 0 || cy < 0) {
            return;
        }

        arrow.reset();
        arrow.moveTo(cx, cy);
        arrow.lineTo(cx, cy + 17f * s);
        arrow.lineTo(cx + 4.6f * s, cy + 12.4f * s);
        arrow.lineTo(cx + 7.2f * s, cy + 18.6f * s);
        arrow.lineTo(cx + 10.4f * s, cy + 17.2f * s);
        arrow.lineTo(cx + 7.9f * s, cy + 11.2f * s);
        arrow.lineTo(cx + 13.2f * s, cy + 11.0f * s);
        arrow.close();

        canvas.drawPath(arrow, fill);
        canvas.drawPath(arrow, edge);

        long dt = System.currentTimeMillis() - pressAt;
        if (dt >= 0 && dt < 260) {
            float p = dt / 260f;
            ripple.setAlpha((int) (200 * (1f - p)));
            canvas.drawCircle(cx + 5f * s, cy + 9f * s, 7f * s + 26f * s * p, ripple);
        }
    }
}
