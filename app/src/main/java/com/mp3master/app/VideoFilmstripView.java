package com.mp3master.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class VideoFilmstripView extends View {

    public interface OnFilmstripListener {
        void onTrimChanged(long startMs, long endMs, boolean isStartHandle, boolean fromUser);
        void onScrub(long positionMs, boolean fromUser);
        void onTouchFinished();
    }

    private static final int MODE_NONE = 0;
    private static final int MODE_START = 1;
    private static final int MODE_END = 2;
    private static final int MODE_PLAYHEAD = 3;

    private long durationMs = 10000L;
    private long trimStartMs = 0L;
    private long trimEndMs = 10000L;
    private long playheadMs = 0L;

    private final List<Bitmap> frames = new ArrayList<>();
    private OnFilmstripListener listener;

    private float density;
    private float handleWidth;
    private float touchSlopRadius;
    private int dragMode = MODE_NONE;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();
    private final RectF tempRect = new RectF();

    public VideoFilmstripView(Context context) {
        super(context);
        init(context);
    }

    public VideoFilmstripView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public VideoFilmstripView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        density = getResources().getDisplayMetrics().density;
        handleWidth = 16f * density;
        touchSlopRadius = 28f * density;

        bgPaint.setColor(Color.parseColor("#0F172A"));
        dimPaint.setColor(Color.parseColor("#B3050811"));

        borderPaint.setColor(ContextCompat.getColor(context, R.color.primary));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f * density);

        handlePaint.setColor(ContextCompat.getColor(context, R.color.primary));

        gripPaint.setColor(ContextCompat.getColor(context, R.color.on_primary));
        gripPaint.setStrokeWidth(2f * density);
        gripPaint.setStrokeCap(Paint.Cap.ROUND);

        playheadPaint.setColor(Color.WHITE);
        playheadPaint.setStrokeWidth(2.5f * density);

        playheadShadowPaint.setColor(Color.parseColor("#88000000"));
        playheadShadowPaint.setStrokeWidth(5f * density);
    }

    public void setListener(OnFilmstripListener listener) {
        this.listener = listener;
    }

    public void setClipData(long durationMs, long startMs, long endMs) {
        this.durationMs = Math.max(500L, durationMs);
        this.trimStartMs = Math.max(0L, Math.min(startMs, this.durationMs - 300L));
        this.trimEndMs = Math.max(this.trimStartMs + 300L, Math.min(endMs > 0 ? endMs : this.durationMs, this.durationMs));
        this.playheadMs = this.trimStartMs;
        clearFrames();
        invalidate();
    }

    public void setFrames(List<Bitmap> newFrames) {
        clearFrames();
        if (newFrames != null) {
            frames.addAll(newFrames);
        }
        invalidate();
    }

    private void clearFrames() {
        frames.clear();
    }

    public void setTrimRange(long startMs, long endMs) {
        this.trimStartMs = Math.max(0L, Math.min(startMs, durationMs - 300L));
        this.trimEndMs = Math.max(this.trimStartMs + 300L, Math.min(endMs, durationMs));
        invalidate();
    }

    public void setPlayheadMs(long ms) {
        this.playheadMs = Math.max(0L, Math.min(ms, durationMs));
        invalidate();
    }

    public long getPlayheadMs() {
        return playheadMs;
    }

    private float getTrackLeft() {
        return handleWidth;
    }

    private float getTrackRight() {
        return Math.max(handleWidth + 10f, getWidth() - handleWidth);
    }

    private float msToX(long ms) {
        float left = getTrackLeft();
        float width = getTrackRight() - left;
        return left + width * ((float) ms / (float) Math.max(1L, durationMs));
    }

    private long xToMs(float x) {
        float left = getTrackLeft();
        float width = Math.max(1f, getTrackRight() - left);
        float frac = Math.max(0f, Math.min(1f, (x - left) / width));
        return Math.round(frac * durationMs);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float top = 6f * density;
        float bottom = h - 6f * density;
        float left = getTrackLeft();
        float right = getTrackRight();
        float trackW = right - left;

        // Track background
        tempRect.set(left, top, right, bottom);
        canvas.drawRoundRect(tempRect, 6f * density, 6f * density, bgPaint);

        // Filmstrip frames
        if (!frames.isEmpty()) {
            float frameW = trackW / frames.size();
            for (int i = 0; i < frames.size(); i++) {
                Bitmap bmp = frames.get(i);
                if (bmp != null && !bmp.isRecycled()) {
                    srcRect.set(0, 0, bmp.getWidth(), bmp.getHeight());
                    dstRect.set(left + i * frameW, top, left + (i + 1) * frameW + 0.5f, bottom);
                    canvas.drawBitmap(bmp, srcRect, dstRect, null);
                }
            }
        }

        float startX = msToX(trimStartMs);
        float endX = msToX(trimEndMs);

        // Dimmed regions outside trim range
        if (startX > left) {
            canvas.drawRect(left, top, startX, bottom, dimPaint);
        }
        if (endX < right) {
            canvas.drawRect(endX, top, right, bottom, dimPaint);
        }

        // Orange top & bottom trim border
        canvas.drawLine(startX, top + 1.5f * density, endX, top + 1.5f * density, borderPaint);
        canvas.drawLine(startX, bottom - 1.5f * density, endX, bottom - 1.5f * density, borderPaint);

        // Left trim handle
        tempRect.set(startX - handleWidth, top, startX, bottom);
        canvas.drawRoundRect(tempRect, 5f * density, 5f * density, handlePaint);
        float midY = (top + bottom) / 2f;
        canvas.drawLine(startX - handleWidth / 2f, midY - 8f * density, startX - handleWidth / 2f, midY + 8f * density, gripPaint);

        // Right trim handle
        tempRect.set(endX, top, endX + handleWidth, bottom);
        canvas.drawRoundRect(tempRect, 5f * density, 5f * density, handlePaint);
        canvas.drawLine(endX + handleWidth / 2f, midY - 8f * density, endX + handleWidth / 2f, midY + 8f * density, gripPaint);

        // White vertical playhead
        float playX = msToX(playheadMs);
        canvas.drawLine(playX, 2f * density, playX, h - 2f * density, playheadShadowPaint);
        canvas.drawLine(playX, 2f * density, playX, h - 2f * density, playheadPaint);
        canvas.drawCircle(playX, 4f * density, 4f * density, playheadPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        float x = event.getX();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                float startCenter = msToX(trimStartMs) - handleWidth / 2f;
                float endCenter = msToX(trimEndMs) + handleWidth / 2f;
                float distStart = Math.abs(x - startCenter);
                float distEnd = Math.abs(x - endCenter);

                if (distStart <= touchSlopRadius && distStart <= distEnd) {
                    dragMode = MODE_START;
                } else if (distEnd <= touchSlopRadius) {
                    dragMode = MODE_END;
                } else {
                    dragMode = MODE_PLAYHEAD;
                    updatePlayheadFromTouch(x);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (dragMode == MODE_START) {
                    long newStart = xToMs(x + handleWidth / 2f);
                    newStart = Math.max(0L, Math.min(newStart, trimEndMs - 300L));
                    if (newStart != trimStartMs) {
                        trimStartMs = newStart;
                        playheadMs = newStart;
                        invalidate();
                        if (listener != null) listener.onTrimChanged(trimStartMs, trimEndMs, true, true);
                    }
                } else if (dragMode == MODE_END) {
                    long newEnd = xToMs(x - handleWidth / 2f);
                    newEnd = Math.min(durationMs, Math.max(newEnd, trimStartMs + 300L));
                    if (newEnd != trimEndMs) {
                        trimEndMs = newEnd;
                        playheadMs = newEnd;
                        invalidate();
                        if (listener != null) listener.onTrimChanged(trimStartMs, trimEndMs, false, true);
                    }
                } else if (dragMode == MODE_PLAYHEAD) {
                    updatePlayheadFromTouch(x);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragMode = MODE_NONE;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (listener != null) listener.onTouchFinished();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void updatePlayheadFromTouch(float x) {
        long ms = xToMs(x);
        ms = Math.max(trimStartMs, Math.min(ms, trimEndMs));
        playheadMs = ms;
        invalidate();
        if (listener != null) listener.onScrub(playheadMs, true);
    }
}
