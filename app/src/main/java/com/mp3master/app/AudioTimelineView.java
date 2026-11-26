package com.mp3master.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

public class AudioTimelineView extends View {

    public interface OnTimelineChangeListener {
        void onTrimRangeChanged(long startMs, long endMs, boolean fromUser);
        void onPlayheadSeek(long positionMs, boolean fromUser);
        void onScaleChanged(float secondsPerCm, boolean isFitAll);
    }

    private static final int MODE_NONE = 0;
    private static final int MODE_START_HANDLE = 1;
    private static final int MODE_END_HANDLE = 2;
    private static final int MODE_PLAYHEAD = 3;
    private static final int MODE_PAN = 4;

    private long durationMs = 60000;
    private long trimStartMs = 0;
    private long trimEndMs = 60000;
    private long playheadMs = 0;

    private float[] waveformPeaks = null;

    // Horizontal scale: pixels per millisecond
    private float pxPerMs = 0.01f;
    private float minPxPerMs = 0.001f;
    private float maxPxPerMs = 2.0f;
    private float scrollX = 0f;
    private boolean fitToWidth = true;

    private float density;
    private float pxPerCm;
    private float touchSlop;
    private float rulerHeight;
    private float scrollBarAreaHeight;
    private float handleTouchRadius;

    private int dragMode = MODE_NONE;
    private float downX = 0f;
    private float lastX = 0f;
    private boolean movedBeyondSlop = false;

    private ScaleGestureDetector scaleDetector;
    private OnTimelineChangeListener listener;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rulerBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rulerTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rulerTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waveActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waveInactivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleGripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrollbarTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrollbarThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF tempRect = new RectF();

    public AudioTimelineView(Context context) {
        super(context);
        init(context);
    }

    public AudioTimelineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public AudioTimelineView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        density = dm.density;
        float xdpi = dm.xdpi > 50f ? dm.xdpi : (density * 160f);
        pxPerCm = xdpi / 2.54f;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        rulerHeight = 24f * density;
        scrollBarAreaHeight = 12f * density;
        handleTouchRadius = 26f * density;

        int primary = ContextCompat.getColor(context, R.color.primary);
        int surfaceBg = ContextCompat.getColor(context, R.color.surface_bg);
        int textSec = ContextCompat.getColor(context, R.color.text_secondary);
        int divider = ContextCompat.getColor(context, R.color.divider);

        bgPaint.setColor(surfaceBg);
        rulerBgPaint.setColor(divider);
        rulerBgPaint.setAlpha(90);

        rulerTickPaint.setColor(textSec);
        rulerTickPaint.setStrokeWidth(1f * density);

        rulerTextPaint.setColor(textSec);
        rulerTextPaint.setTextSize(10f * density);

        waveActivePaint.setColor(primary);
        waveActivePaint.setStrokeCap(Paint.Cap.ROUND);

        waveInactivePaint.setColor(textSec);
        waveInactivePaint.setAlpha(110);
        waveInactivePaint.setStrokeCap(Paint.Cap.ROUND);

        dimOverlayPaint.setColor(Color.BLACK);
        dimOverlayPaint.setAlpha(45);

        selectionBorderPaint.setColor(primary);
        selectionBorderPaint.setStyle(Paint.Style.STROKE);
        selectionBorderPaint.setStrokeWidth(2f * density);

        handlePaint.setColor(primary);
        handleGripPaint.setColor(Color.WHITE);
        handleGripPaint.setStrokeWidth(1.5f * density);
        handleGripPaint.setStrokeCap(Paint.Cap.ROUND);

        playheadPaint.setColor(Color.parseColor("#EF4444"));
        playheadPaint.setStrokeWidth(2.2f * density);

        scrollbarTrackPaint.setColor(divider);
        scrollbarThumbPaint.setColor(textSec);
        scrollbarThumbPaint.setAlpha(170);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                zoomBy(detector.getScaleFactor(), detector.getFocusX());
                return true;
            }
        });
    }

    public void setListener(OnTimelineChangeListener listener) {
        this.listener = listener;
    }

    public void setAudioData(long durationMs, long startMs, long endMs) {
        this.durationMs = Math.max(1000L, durationMs);
        this.trimStartMs = Math.max(0L, Math.min(startMs, this.durationMs - 200L));
        this.trimEndMs = Math.max(this.trimStartMs + 200L, Math.min(endMs > 0 ? endMs : this.durationMs, this.durationMs));
        this.playheadMs = this.trimStartMs;
        this.waveformPeaks = null;
        this.fitToWidth = true;
        this.scrollX = 0f;
        recalculateScaleBounds();
        invalidate();
        notifyScale();
    }

    public void setWaveformPeaks(float[] peaks) {
        this.waveformPeaks = peaks;
        invalidate();
    }

    public void setTrimRange(long startMs, long endMs) {
        this.trimStartMs = Math.max(0L, Math.min(startMs, durationMs - 200L));
        this.trimEndMs = Math.max(this.trimStartMs + 200L, Math.min(endMs, durationMs));
        invalidate();
    }

    public long getTrimStartMs() {
        return trimStartMs;
    }

    public long getTrimEndMs() {
        return trimEndMs;
    }

    public long getPlayheadMs() {
        return playheadMs;
    }

    public void setPlayheadMs(long ms, boolean autoScroll) {
        this.playheadMs = Math.max(0L, Math.min(ms, durationMs));
        if (autoScroll && !fitToWidth && getWidth() > 0) {
            float x = msToScreenX(this.playheadMs);
            float margin = getWidth() * 0.15f;
            if (x < margin || x > getWidth() - margin) {
                scrollToMs(this.playheadMs);
            }
        }
        invalidate();
    }

    public void scrollToMs(long ms) {
        if (getWidth() <= 0 || fitToWidth) return;
        float targetX = ms * pxPerMs - getWidth() / 2f;
        scrollX = clampScroll(targetX);
        invalidate();
    }

    public void setFitToWidth() {
        fitToWidth = true;
        recalculateScaleBounds();
        pxPerMs = minPxPerMs;
        scrollX = 0f;
        invalidate();
        notifyScale();
    }

    public void setSecondsPerCm(float secondsPerCm) {
        if (getWidth() <= 0 || durationMs <= 0) return;
        recalculateScaleBounds();
        float desiredPxPerMs = pxPerCm / (secondsPerCm * 1000f);
        float centerMs = screenXToMs(getWidth() / 2f);
        if (playheadMs >= 0 && playheadMs <= durationMs) {
            centerMs = playheadMs;
        }
        if (desiredPxPerMs <= minPxPerMs * 1.02f) {
            setFitToWidth();
            return;
        }
        fitToWidth = false;
        pxPerMs = Math.max(minPxPerMs, Math.min(maxPxPerMs, desiredPxPerMs));
        scrollX = clampScroll(centerMs * pxPerMs - getWidth() / 2f);
        invalidate();
        notifyScale();
    }

    public void zoomBy(float factor, float focusX) {
        if (getWidth() <= 0 || durationMs <= 0) return;
        recalculateScaleBounds();
        float focusMs = screenXToMs(focusX);
        float newPxPerMs = Math.max(minPxPerMs, Math.min(maxPxPerMs, pxPerMs * factor));
        fitToWidth = (newPxPerMs <= minPxPerMs * 1.02f);
        pxPerMs = fitToWidth ? minPxPerMs : newPxPerMs;
        scrollX = fitToWidth ? 0f : clampScroll(focusMs * pxPerMs - focusX);
        invalidate();
        notifyScale();
    }

    public float getCurrentSecondsPerCm() {
        if (pxPerMs <= 0f) return 0f;
        return (pxPerCm / pxPerMs) / 1000f;
    }

    public boolean isFitToWidth() {
        return fitToWidth;
    }

    private void notifyScale() {
        if (listener != null) {
            listener.onScaleChanged(getCurrentSecondsPerCm(), fitToWidth);
        }
    }

    private void recalculateScaleBounds() {
        int w = getWidth();
        if (w <= 0 || durationMs <= 0) return;
        minPxPerMs = (float) w / (float) durationMs;
        // Max zoom: 0.25 sec per cm (very precise!)
        float highZoom = pxPerCm / 250f;
        maxPxPerMs = Math.max(minPxPerMs * 2f, highZoom);
        if (fitToWidth || pxPerMs < minPxPerMs) {
            pxPerMs = minPxPerMs;
            scrollX = 0f;
        } else {
            pxPerMs = Math.min(pxPerMs, maxPxPerMs);
            scrollX = clampScroll(scrollX);
        }
    }

    private float clampScroll(float target) {
        float maxScroll = Math.max(0f, durationMs * pxPerMs - getWidth());
        return Math.max(0f, Math.min(target, maxScroll));
    }

    private float msToScreenX(long ms) {
        return ms * pxPerMs - scrollX;
    }

    private long screenXToMs(float x) {
        if (pxPerMs <= 0f) return 0L;
        long ms = Math.round((x + scrollX) / pxPerMs);
        return Math.max(0L, Math.min(ms, durationMs));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recalculateScaleBounds();
        notifyScale();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || durationMs <= 0) return;

        float waveTop = rulerHeight;
        float waveBottom = h - scrollBarAreaHeight;
        float waveHeight = Math.max(10f, waveBottom - waveTop);
        float waveCenterY = waveTop + waveHeight / 2f;

        // Background
        canvas.drawRect(0, 0, w, h, bgPaint);
        canvas.drawRect(0, 0, w, rulerHeight, rulerBgPaint);

        // Time Ruler ticks
        drawTimeRuler(canvas, w);

        // Waveform
        drawWaveform(canvas, w, waveTop, waveBottom, waveCenterY, waveHeight);

        // Trim region overlay & handles
        float startX = msToScreenX(trimStartMs);
        float endX = msToScreenX(trimEndMs);

        if (startX > 0) {
            canvas.drawRect(0, waveTop, Math.min(w, startX), waveBottom, dimOverlayPaint);
        }
        if (endX < w) {
            canvas.drawRect(Math.max(0, endX), waveTop, w, waveBottom, dimOverlayPaint);
        }

        // Highlight border inside selection
        float selLeft = Math.max(-4f, startX);
        float selRight = Math.min(w + 4f, endX);
        if (selRight > selLeft) {
            canvas.drawLine(selLeft, waveTop + 1f * density, selRight, waveTop + 1f * density, selectionBorderPaint);
            canvas.drawLine(selLeft, waveBottom - 1f * density, selRight, waveBottom - 1f * density, selectionBorderPaint);
        }

        // Left & Right Trim Handles
        drawTrimHandle(canvas, startX, waveTop, waveBottom, true);
        drawTrimHandle(canvas, endX, waveTop, waveBottom, false);

        // Playhead
        float playX = msToScreenX(playheadMs);
        if (playX >= -12f * density && playX <= w + 12f * density) {
            canvas.drawLine(playX, rulerHeight * 0.45f, playX, waveBottom, playheadPaint);
            canvas.drawCircle(playX, rulerHeight * 0.55f, 5.5f * density, playheadPaint);
        }

        // Bottom scrollbar when zoomed in
        drawScrollbar(canvas, w, h, waveBottom);
    }

    private void drawTimeRuler(Canvas canvas, int w) {
        // Choose readable tick interval so ticks are at least ~55dp apart
        long[] stepsMs = {500, 1000, 2000, 5000, 10000, 15000, 30000, 60000, 120000, 300000, 600000};
        long minMsPerLabel = Math.round((56f * density) / pxPerMs);
        long stepMs = stepsMs[stepsMs.length - 1];
        for (long candidate : stepsMs) {
            if (candidate >= minMsPerLabel) {
                stepMs = candidate;
                break;
            }
        }

        long visibleStartMs = screenXToMs(0);
        long visibleEndMs = screenXToMs(w);
        long firstTick = (visibleStartMs / stepMs) * stepMs;

        for (long t = firstTick; t <= visibleEndMs + stepMs; t += stepMs) {
            if (t < 0 || t > durationMs) continue;
            float x = msToScreenX(t);
            canvas.drawLine(x, rulerHeight - 6f * density, x, rulerHeight, rulerTickPaint);
            String label = stepMs < 1000 ? AudioUtils.formatTimePreciseMs(t) : AudioUtils.formatTimeMs(t);
            canvas.drawText(label, x + 3f * density, rulerHeight - 8f * density, rulerTextPaint);
        }
    }

    private void drawWaveform(Canvas canvas, int w, float top, float bottom, float centerY, float height) {
        float maxBarHalf = (height * 0.42f);
        if (waveformPeaks == null || waveformPeaks.length == 0) {
            // Subtle placeholder center line while peaks load
            waveInactivePaint.setStrokeWidth(2f * density);
            canvas.drawLine(0, centerY, w, centerY, waveInactivePaint);
            return;
        }

        float stepPx = 3f * density;
        waveActivePaint.setStrokeWidth(2f * density);
        waveInactivePaint.setStrokeWidth(2f * density);

        int n = waveformPeaks.length;
        for (float x = 0; x < w; x += stepPx) {
            long ms = screenXToMs(x);
            int idx = (int) ((ms * (long) (n - 1)) / Math.max(1L, durationMs));
            idx = Math.max(0, Math.min(n - 1, idx));
            float amp = Math.max(0.06f, waveformPeaks[idx]);
            float halfH = amp * maxBarHalf;
            Paint p = (ms >= trimStartMs && ms <= trimEndMs) ? waveActivePaint : waveInactivePaint;
            canvas.drawLine(x, centerY - halfH, x, centerY + halfH, p);
        }
    }

    private void drawTrimHandle(Canvas canvas, float x, float top, float bottom, boolean isLeft) {
        if (x < -30f * density || x > getWidth() + 30f * density) return;

        float barW = 3f * density;
        canvas.drawRect(x - barW / 2f, top, x + barW / 2f, bottom, handlePaint);

        float pillW = 14f * density;
        float pillH = 34f * density;
        float midY = (top + bottom) / 2f;
        float left = isLeft ? (x - pillW) : x;
        float right = isLeft ? x : (x + pillW);

        tempRect.set(left, midY - pillH / 2f, right, midY + pillH / 2f);
        canvas.drawRoundRect(tempRect, 4f * density, 4f * density, handlePaint);

        float gripX = (left + right) / 2f;
        canvas.drawLine(gripX, midY - 7f * density, gripX, midY + 7f * density, handleGripPaint);
    }

    private void drawScrollbar(Canvas canvas, int w, int h, float top) {
        float barH = 4f * density;
        float y = top + (scrollBarAreaHeight - barH) / 2f;
        tempRect.set(6f * density, y, w - 6f * density, y + barH);
        canvas.drawRoundRect(tempRect, 2f * density, 2f * density, scrollbarTrackPaint);

        float totalWidth = Math.max(w, durationMs * pxPerMs);
        float trackW = w - 12f * density;
        float thumbW = Math.max(24f * density, trackW * (w / totalWidth));
        float maxScroll = Math.max(1f, totalWidth - w);
        float thumbLeft = 6f * density + (trackW - thumbW) * (scrollX / maxScroll);

        tempRect.set(thumbLeft, y, thumbLeft + thumbW, y + barH);
        canvas.drawRoundRect(tempRect, 2f * density, 2f * density, scrollbarThumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }

        scaleDetector.onTouchEvent(event);
        if (scaleDetector.isInProgress()) {
            dragMode = MODE_NONE;
            return true;
        }

        float x = event.getX();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                lastX = x;
                movedBeyondSlop = false;

                float startX = msToScreenX(trimStartMs);
                float endX = msToScreenX(trimEndMs);
                float playX = msToScreenX(playheadMs);

                float distStart = Math.abs(x - (startX - 7f * density));
                float distEnd = Math.abs(x - (endX + 7f * density));
                float distPlay = Math.abs(x - playX);

                if (distStart <= handleTouchRadius && distStart <= distEnd) {
                    dragMode = MODE_START_HANDLE;
                } else if (distEnd <= handleTouchRadius) {
                    dragMode = MODE_END_HANDLE;
                } else if (distPlay <= 20f * density && event.getY() <= rulerHeight * 1.8f) {
                    dragMode = MODE_PLAYHEAD;
                } else {
                    dragMode = MODE_PAN;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = x - lastX;
                if (Math.abs(x - downX) > touchSlop) {
                    movedBeyondSlop = true;
                }
                lastX = x;

                if (dragMode == MODE_START_HANDLE) {
                    long newStart = screenXToMs(x);
                    newStart = Math.max(0L, Math.min(newStart, trimEndMs - 300L));
                    if (newStart != trimStartMs) {
                        trimStartMs = newStart;
                        invalidate();
                        if (listener != null) listener.onTrimRangeChanged(trimStartMs, trimEndMs, true);
                    }
                } else if (dragMode == MODE_END_HANDLE) {
                    long newEnd = screenXToMs(x);
                    newEnd = Math.min(durationMs, Math.max(newEnd, trimStartMs + 300L));
                    if (newEnd != trimEndMs) {
                        trimEndMs = newEnd;
                        invalidate();
                        if (listener != null) listener.onTrimRangeChanged(trimStartMs, trimEndMs, true);
                    }
                } else if (dragMode == MODE_PLAYHEAD) {
                    long newPlay = screenXToMs(x);
                    playheadMs = newPlay;
                    invalidate();
                    if (listener != null) listener.onPlayheadSeek(playheadMs, true);
                } else if (dragMode == MODE_PAN && movedBeyondSlop) {
                    if (!fitToWidth) {
                        scrollX = clampScroll(scrollX - dx);
                        invalidate();
                    } else {
                        // When not zoomed in, dragging directly scrubs the playhead
                        long newPlay = screenXToMs(x);
                        playheadMs = newPlay;
                        invalidate();
                        if (listener != null) listener.onPlayheadSeek(playheadMs, true);
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (dragMode == MODE_PAN && !movedBeyondSlop) {
                    // Single tap anywhere on timeline seeks playhead there!
                    playheadMs = screenXToMs(x);
                    invalidate();
                    if (listener != null) listener.onPlayheadSeek(playheadMs, true);
                }
                dragMode = MODE_NONE;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                dragMode = MODE_NONE;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
        }
        return super.onTouchEvent(event);
    }
}
