package com.mp3master.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VideoFilmstripView extends View {

    public interface OnFilmstripListener {
        void onClipSelected(int clipIndex);
        void onScrub(int clipIndex, long clipPositionMs, boolean fromUser);
        void onClipSwipedDelete(int clipIndex);
        void onClipMoved(int fromIndex, int toIndex);
        void onScaleChanged(float secondsPerCm, boolean isFitAll);
        void onTouchFinished();
    }

    private static final int GESTURE_NONE = 0;
    private static final int GESTURE_SCRUB = 1;
    private static final int GESTURE_PAN = 2;
    private static final int GESTURE_SWIPE_DELETE = 3;
    private static final int GESTURE_REORDER_DRAG = 4;

    private final List<AudioUtils.VideoInfo> clips = new ArrayList<>();
    private final Map<String, List<Bitmap>> frameCache = new HashMap<>();
    private int activeClipIndex = 0;
    private long activeClipPlayheadMs = 0L;

    // Zoom & horizontal scroll
    private float pxPerMs = 0.02f;
    private float minPxPerMs = 0.005f;
    private float maxPxPerMs = 2.0f;
    private float scrollX = 0f;
    private boolean fitToWidth = true;

    // Reorder mode (long-press lifts clip & shows left/right arrows)
    private int reorderClipIndex = -1;
    private float reorderDragX = 0f;

    private OnFilmstripListener listener;
    private ScaleGestureDetector scaleDetector;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float density;
    private float pxPerCm;
    private float dividerGapPx;
    private float touchSlop;
    private float deleteThresholdPx;
    private float liftOffsetPx;
    private float scrollbarHeightPx;

    private int gestureMode = GESTURE_NONE;
    private int touchedClipIndex = -1;
    private float downX, downY, lastX;
    private float swipeOffsetY = 0f;
    private boolean longPressTriggered = false;

    private final RectF leftArrowRect = new RectF();
    private final RectF rightArrowRect = new RectF();

    private final Paint clipBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activeBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint liftedShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint deleteTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrollTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrollThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();
    private final RectF clipRect = new RectF();
    private final RectF tempRect = new RectF();

    private final Runnable longPressRunnable = () -> {
        if (gestureMode == GESTURE_NONE && touchedClipIndex >= 0 && touchedClipIndex < clips.size() && clips.size() > 1) {
            longPressTriggered = true;
            reorderClipIndex = touchedClipIndex;
            activeClipIndex = touchedClipIndex;
            gestureMode = GESTURE_REORDER_DRAG;
            reorderDragX = downX;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            invalidate();
        }
    };

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
        DisplayMetrics dm = getResources().getDisplayMetrics();
        density = dm.density;
        float xdpi = dm.xdpi > 50f ? dm.xdpi : (density * 160f);
        pxPerCm = xdpi / 2.54f;

        dividerGapPx = 6f * density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        deleteThresholdPx = 42f * density;
        liftOffsetPx = 11f * density;
        scrollbarHeightPx = 10f * density;

        int primary = ContextCompat.getColor(context, R.color.primary);
        int onPrimary = ContextCompat.getColor(context, R.color.on_primary);
        int divider = ContextCompat.getColor(context, R.color.divider);
        int textSec = ContextCompat.getColor(context, R.color.text_secondary);

        clipBgPaint.setColor(ContextCompat.getColor(context, R.color.pill_bg));

        activeBorderPaint.setColor(primary);
        activeBorderPaint.setStyle(Paint.Style.STROKE);
        activeBorderPaint.setStrokeWidth(3f * density);

        liftedShadowPaint.setColor(Color.parseColor("#55000000"));

        badgeBgPaint.setColor(Color.parseColor("#B8000000"));
        badgeTextPaint.setColor(Color.WHITE);
        badgeTextPaint.setTextSize(10f * density);
        badgeTextPaint.setFakeBoldText(true);

        playheadPaint.setColor(Color.WHITE);
        playheadPaint.setStrokeWidth(2.5f * density);

        playheadShadowPaint.setColor(Color.parseColor("#99000000"));
        playheadShadowPaint.setStrokeWidth(5f * density);

        deleteTintPaint.setColor(Color.parseColor("#88DC2626"));

        arrowBgPaint.setColor(primary);
        arrowTextPaint.setColor(onPrimary);
        arrowTextPaint.setTextSize(15f * density);
        arrowTextPaint.setFakeBoldText(true);
        arrowTextPaint.setTextAlign(Paint.Align.CENTER);

        scrollTrackPaint.setColor(divider);
        scrollThumbPaint.setColor(textSec);
        scrollThumbPaint.setAlpha(170);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                handler.removeCallbacks(longPressRunnable);
                reorderClipIndex = -1;
                zoomBy(detector.getScaleFactor(), detector.getFocusX());
                return true;
            }
        });
    }

    public void setListener(OnFilmstripListener listener) {
        this.listener = listener;
    }

    public void setClips(List<AudioUtils.VideoInfo> newClips, int activeIdx, long playheadMs) {
        this.clips.clear();
        if (newClips != null) {
            this.clips.addAll(newClips);
        }
        this.activeClipIndex = Math.max(0, Math.min(activeIdx, Math.max(0, clips.size() - 1)));
        this.activeClipPlayheadMs = playheadMs;
        if (reorderClipIndex >= clips.size()) {
            reorderClipIndex = -1;
        }
        this.swipeOffsetY = 0f;
        this.gestureMode = GESTURE_NONE;
        this.touchedClipIndex = -1;
        recalculateScaleBounds();
        invalidate();
        notifyScale();
    }

    public void exitReorderMode() {
        if (reorderClipIndex != -1) {
            reorderClipIndex = -1;
            invalidate();
        }
    }

    public void putFramesForUri(String uriKey, List<Bitmap> frames) {
        if (uriKey != null && frames != null && !frames.isEmpty()) {
            frameCache.put(uriKey, frames);
            invalidate();
        }
    }

    public boolean hasFramesForUri(String uriKey) {
        List<Bitmap> list = frameCache.get(uriKey);
        return list != null && !list.isEmpty();
    }

    public void setActivePlayhead(int clipIndex, long positionMs) {
        this.activeClipIndex = Math.max(0, Math.min(clipIndex, Math.max(0, clips.size() - 1)));
        this.activeClipPlayheadMs = positionMs;
        invalidate();
    }

    public long getPlayheadMs() {
        return activeClipPlayheadMs;
    }

    public void zoomBy(float factor, float focusX) {
        if (getWidth() <= 0 || clips.isEmpty()) return;
        recalculateScaleBounds();
        float contentFocusX = focusX + scrollX;
        float ratio = (pxPerMs > 0f) ? (contentFocusX / pxPerMs) : 0f;

        float newPxPerMs = Math.max(minPxPerMs, Math.min(maxPxPerMs, pxPerMs * factor));
        fitToWidth = (newPxPerMs <= minPxPerMs * 1.02f);
        pxPerMs = fitToWidth ? minPxPerMs : newPxPerMs;
        scrollX = fitToWidth ? 0f : clampScroll(ratio * pxPerMs - focusX);
        invalidate();
        notifyScale();
    }

    public float getCurrentSecondsPerCm() {
        if (pxPerMs <= 0f) return 0f;
        return (pxPerCm / pxPerMs) / 1000f;
    }

    private void notifyScale() {
        if (listener != null) {
            listener.onScaleChanged(getCurrentSecondsPerCm(), fitToWidth);
        }
    }

    private long getTotalEffectiveMs() {
        long sum = 0;
        for (AudioUtils.VideoInfo c : clips) {
            sum += Math.max(300L, c.getEffectiveDurationMs());
        }
        return Math.max(300L, sum);
    }

    private void recalculateScaleBounds() {
        int w = getWidth();
        if (w <= 0 || clips.isEmpty()) return;
        float padX = 4f * density;
        float totalGaps = Math.max(0, clips.size() - 1) * dividerGapPx;
        float availW = Math.max(20f, w - padX * 2f - totalGaps);
        long totalMs = getTotalEffectiveMs();

        minPxPerMs = availW / (float) totalMs;
        // Up to 0.25 sec per cm (so 1 sec/cm is easily in the middle of zoom range)
        float highZoom = pxPerCm / 250f;
        maxPxPerMs = Math.max(minPxPerMs * 2f, highZoom);

        if (fitToWidth || pxPerMs < minPxPerMs) {
            fitToWidth = true;
            pxPerMs = minPxPerMs;
            scrollX = 0f;
        } else {
            pxPerMs = Math.min(pxPerMs, maxPxPerMs);
            scrollX = clampScroll(scrollX);
        }
    }

    private float getTotalContentWidth() {
        float padX = 4f * density;
        float totalGaps = Math.max(0, clips.size() - 1) * dividerGapPx;
        return padX * 2f + totalGaps + getTotalEffectiveMs() * pxPerMs;
    }

    private float clampScroll(float target) {
        float maxScroll = Math.max(0f, getTotalContentWidth() - getWidth());
        return Math.max(0f, Math.min(target, maxScroll));
    }

    private float[] computeClipBoundsX() {
        int n = clips.size();
        float[] bounds = new float[n * 2];
        if (n == 0) return bounds;
        float curX = 4f * density - scrollX;
        for (int i = 0; i < n; i++) {
            long dur = Math.max(300L, clips.get(i).getEffectiveDurationMs());
            float w = Math.max(20f * density, dur * pxPerMs);
            bounds[i * 2] = curX;
            bounds[i * 2 + 1] = curX + w;
            curX += w + dividerGapPx;
        }
        return bounds;
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
        if (w <= 0 || h <= 0 || clips.isEmpty()) return;

        float topBase = liftOffsetPx + 4f * density;
        float bottomBase = h - scrollbarHeightPx - 4f * density;
        float[] bounds = computeClipBoundsX();

        leftArrowRect.setEmpty();
        rightArrowRect.setEmpty();

        // Draw non-lifted clips first, then lifted clip on top
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < clips.size(); i++) {
                boolean isLifted = (i == reorderClipIndex);
                if ((pass == 0 && isLifted) || (pass == 1 && !isLifted)) continue;

                AudioUtils.VideoInfo clip = clips.get(i);
                float left = bounds[i * 2];
                float right = bounds[i * 2 + 1];
                if (right < -40f || left > w + 40f) continue;

                float segW = right - left;
                float dy = 0f;
                if (isLifted) {
                    dy = -liftOffsetPx;
                } else if (gestureMode == GESTURE_SWIPE_DELETE && i == touchedClipIndex) {
                    dy = swipeOffsetY;
                }

                float top = topBase + dy;
                float bottom = bottomBase + dy;

                if (isLifted) {
                    tempRect.set(left + 2f * density, top + 4f * density, right + 2f * density, bottom + 5f * density);
                    canvas.drawRoundRect(tempRect, 6f * density, 6f * density, liftedShadowPaint);
                }

                clipRect.set(left, top, right, bottom);
                canvas.save();
                canvas.clipRect(clipRect);
                canvas.drawRect(clipRect, clipBgPaint);

                // Draw real frames
                List<Bitmap> allFrames = frameCache.get(clip.uri.toString());
                if (allFrames != null && !allFrames.isEmpty()) {
                    int totalFrames = allFrames.size();
                    float startFrac = clip.durationMs > 0 ? ((float) clip.trimStartMs / clip.durationMs) : 0f;
                    float endFrac = clip.durationMs > 0 ? ((float) clip.trimEndMs / clip.durationMs) : 1f;
                    int fStart = Math.max(0, Math.min(totalFrames - 1, Math.round(startFrac * (totalFrames - 1))));
                    int fEnd = Math.max(fStart, Math.min(totalFrames - 1, Math.round(endFrac * (totalFrames - 1))));

                    int tileCount = Math.max(1, Math.min(36, Math.round(segW / (44f * density))));
                    float tileW = segW / tileCount;
                    for (int t = 0; t < tileCount; t++) {
                        float tLeft = left + t * tileW;
                        float tRight = tLeft + tileW + 0.5f;
                        if (tRight < 0 || tLeft > w) continue;
                        float fRatio = (tileCount == 1) ? 0.5f : ((float) t / (tileCount - 1));
                        int frameIdx = Math.round(fStart + fRatio * (fEnd - fStart));
                        frameIdx = Math.max(0, Math.min(totalFrames - 1, frameIdx));
                        Bitmap bmp = allFrames.get(frameIdx);
                        if (bmp != null && !bmp.isRecycled()) {
                            srcRect.set(0, 0, bmp.getWidth(), bmp.getHeight());
                            dstRect.set(tLeft, top, tRight, bottom);
                            canvas.drawBitmap(bmp, srcRect, dstRect, null);
                        }
                    }
                }

                if (gestureMode == GESTURE_SWIPE_DELETE && i == touchedClipIndex && Math.abs(swipeOffsetY) > deleteThresholdPx * 0.5f) {
                    canvas.drawRect(clipRect, deleteTintPaint);
                }

                if (segW > 28f * density) {
                    String label = (i + 1) + " • " + AudioUtils.formatTimeMs(clip.getEffectiveDurationMs());
                    float tw = badgeTextPaint.measureText(label);
                    float bx = left + 4f * density;
                    float by = bottom - 4f * density;
                    canvas.drawRect(bx - 2f * density, by - 12f * density, bx + tw + 3f * density, by + 2f * density, badgeBgPaint);
                    canvas.drawText(label, bx, by - 2f * density, badgeTextPaint);
                }

                canvas.restore();

                if (i == activeClipIndex || isLifted) {
                    canvas.drawRect(left + 1.5f * density, top + 1.5f * density, right - 1.5f * density, bottom - 1.5f * density, activeBorderPaint);
                }

                // Draw Left / Right arrow buttons on lifted clip (Method 2 of reordering)
                if (isLifted && clips.size() > 1) {
                    float btnSize = 26f * density;
                    float midY = (top + bottom) / 2f;
                    Paint.FontMetrics fm = arrowTextPaint.getFontMetrics();
                    float textBaseline = midY - (fm.ascent + fm.descent) / 2f;

                    if (i > 0) {
                        float lx = Math.max(4f * density, left + 4f * density);
                        leftArrowRect.set(lx, midY - btnSize / 2f, lx + btnSize, midY + btnSize / 2f);
                        canvas.drawCircle(leftArrowRect.centerX(), leftArrowRect.centerY(), btnSize / 2f, arrowBgPaint);
                        canvas.drawText("◄", leftArrowRect.centerX(), textBaseline, arrowTextPaint);
                    }
                    if (i < clips.size() - 1) {
                        float rx = Math.min(w - btnSize - 4f * density, right - btnSize - 4f * density);
                        rightArrowRect.set(rx, midY - btnSize / 2f, rx + btnSize, midY + btnSize / 2f);
                        canvas.drawCircle(rightArrowRect.centerX(), rightArrowRect.centerY(), btnSize / 2f, arrowBgPaint);
                        canvas.drawText("►", rightArrowRect.centerX(), textBaseline, arrowTextPaint);
                    }
                }
            }
        }

        // White vertical playhead on active clip
        if (activeClipIndex >= 0 && activeClipIndex < clips.size() && reorderClipIndex == -1) {
            AudioUtils.VideoInfo active = clips.get(activeClipIndex);
            float left = bounds[activeClipIndex * 2];
            float right = bounds[activeClipIndex * 2 + 1];
            long effDur = Math.max(1L, active.getEffectiveDurationMs());
            float frac = Math.max(0f, Math.min(1f, (float) (activeClipPlayheadMs - active.trimStartMs) / (float) effDur));
            float playX = left + (right - left) * frac;

            if (playX >= -10f && playX <= w + 10f) {
                canvas.drawLine(playX, topBase - 4f * density, playX, bottomBase + 2f * density, playheadShadowPaint);
                canvas.drawLine(playX, topBase - 4f * density, playX, bottomBase + 2f * density, playheadPaint);
                canvas.drawCircle(playX, topBase - 4f * density, 4f * density, playheadPaint);
            }
        }

        // Bottom scrollbar when zoomed in
        float barH = 3.5f * density;
        float barY = h - scrollbarHeightPx / 2f - barH / 2f;
        tempRect.set(6f * density, barY, w - 6f * density, barY + barH);
        canvas.drawRoundRect(tempRect, 2f * density, 2f * density, scrollTrackPaint);

        float totalW = Math.max(w, getTotalContentWidth());
        float trackW = w - 12f * density;
        float thumbW = Math.max(24f * density, trackW * (w / totalW));
        float maxScroll = Math.max(1f, totalW - w);
        float thumbLeft = 6f * density + (trackW - thumbW) * (scrollX / maxScroll);
        tempRect.set(thumbLeft, barY, thumbLeft + thumbW, barY + barH);
        canvas.drawRoundRect(tempRect, 2f * density, 2f * density, scrollThumbPaint);
    }

    private int findClipAtX(float x, float[] bounds) {
        for (int i = 0; i < clips.size(); i++) {
            if (x >= bounds[i * 2] - dividerGapPx / 2f && x <= bounds[i * 2 + 1] + dividerGapPx / 2f) {
                return i;
            }
        }
        return clips.isEmpty() ? -1 : Math.max(0, Math.min(clips.size() - 1, activeClipIndex));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (clips.isEmpty()) return false;
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }

        scaleDetector.onTouchEvent(event);
        if (scaleDetector.isInProgress()) {
            handler.removeCallbacks(longPressRunnable);
            gestureMode = GESTURE_NONE;
            return true;
        }

        float x = event.getX();
        float y = event.getY();
        float[] bounds = computeClipBoundsX();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                lastX = x;
                swipeOffsetY = 0f;
                longPressTriggered = false;
                gestureMode = GESTURE_NONE;
                touchedClipIndex = findClipAtX(x, bounds);

                // Check if user tapped Left / Right arrow on currently lifted clip
                if (reorderClipIndex >= 0 && reorderClipIndex < clips.size()) {
                    float pad = 8f * density;
                    if (!leftArrowRect.isEmpty() && x >= leftArrowRect.left - pad && x <= leftArrowRect.right + pad
                            && y >= leftArrowRect.top - pad && y <= leftArrowRect.bottom + pad) {
                        moveClipStep(reorderClipIndex, reorderClipIndex - 1);
                        return true;
                    }
                    if (!rightArrowRect.isEmpty() && x >= rightArrowRect.left - pad && x <= rightArrowRect.right + pad
                            && y >= rightArrowRect.top - pad && y <= rightArrowRect.bottom + pad) {
                        moveClipStep(reorderClipIndex, reorderClipIndex + 1);
                        return true;
                    }
                    // If user tapped outside the lifted clip, exit reorder mode
                    if (touchedClipIndex != reorderClipIndex) {
                        reorderClipIndex = -1;
                        invalidate();
                    } else {
                        // Pressing on the already-lifted clip lets them immediately drag it left/right
                        gestureMode = GESTURE_REORDER_DRAG;
                        reorderDragX = x;
                        return true;
                    }
                }

                handler.removeCallbacks(longPressRunnable);
                if (clips.size() > 1) {
                    handler.postDelayed(longPressRunnable, 360);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                float dxTotal = x - downX;
                float dyTotal = y - downY;
                float dxStep = x - lastX;
                lastX = x;

                if (gestureMode == GESTURE_REORDER_DRAG && reorderClipIndex >= 0) {
                    // Check if dragged past neighbor midpoint -> swap!
                    if (reorderClipIndex > 0) {
                        float prevMid = (bounds[(reorderClipIndex - 1) * 2] + bounds[(reorderClipIndex - 1) * 2 + 1]) / 2f;
                        if (x < prevMid) {
                            moveClipStep(reorderClipIndex, reorderClipIndex - 1);
                            return true;
                        }
                    }
                    if (reorderClipIndex < clips.size() - 1) {
                        float nextMid = (bounds[(reorderClipIndex + 1) * 2] + bounds[(reorderClipIndex + 1) * 2 + 1]) / 2f;
                        if (x > nextMid) {
                            moveClipStep(reorderClipIndex, reorderClipIndex + 1);
                            return true;
                        }
                    }
                    return true;
                }

                if (gestureMode == GESTURE_NONE) {
                    if (Math.abs(dxTotal) > touchSlop || Math.abs(dyTotal) > touchSlop) {
                        handler.removeCallbacks(longPressRunnable);
                        if (Math.abs(dyTotal) > Math.abs(dxTotal) * 1.25f) {
                            gestureMode = GESTURE_SWIPE_DELETE;
                        } else if (!fitToWidth) {
                            gestureMode = GESTURE_PAN;
                        } else {
                            gestureMode = GESTURE_SCRUB;
                        }
                    }
                }

                if (gestureMode == GESTURE_SWIPE_DELETE) {
                    swipeOffsetY = dyTotal;
                    invalidate();
                } else if (gestureMode == GESTURE_PAN) {
                    scrollX = clampScroll(scrollX - dxStep);
                    invalidate();
                } else if (gestureMode == GESTURE_SCRUB) {
                    scrubAtX(x, bounds);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressRunnable);
                if (gestureMode == GESTURE_SWIPE_DELETE) {
                    if (Math.abs(swipeOffsetY) >= deleteThresholdPx && touchedClipIndex >= 0 && touchedClipIndex < clips.size()) {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        int delIdx = touchedClipIndex;
                        swipeOffsetY = 0f;
                        gestureMode = GESTURE_NONE;
                        if (listener != null) listener.onClipSwipedDelete(delIdx);
                    } else {
                        swipeOffsetY = 0f;
                        gestureMode = GESTURE_NONE;
                        invalidate();
                    }
                } else if (gestureMode == GESTURE_REORDER_DRAG) {
                    // Keep clip lifted with ◄ ► arrows visible so user can also tap arrows!
                    invalidate();
                } else if (gestureMode == GESTURE_NONE) {
                    // Single tap -> select clip & seek playhead right there
                    scrubAtX(x, bounds);
                    if (listener != null) listener.onTouchFinished();
                } else if (gestureMode == GESTURE_SCRUB) {
                    if (listener != null) listener.onTouchFinished();
                }

                gestureMode = GESTURE_NONE;
                touchedClipIndex = -1;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void moveClipStep(int from, int to) {
        if (from < 0 || from >= clips.size() || to < 0 || to >= clips.size() || from == to) return;
        Collections.swap(clips, from, to);
        reorderClipIndex = to;
        activeClipIndex = to;
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        invalidate();
        if (listener != null) {
            listener.onClipMoved(from, to);
        }
    }

    private void scrubAtX(float x, float[] bounds) {
        int idx = findClipAtX(x, bounds);
        if (idx < 0 || idx >= clips.size()) return;
        AudioUtils.VideoInfo clip = clips.get(idx);
        float left = bounds[idx * 2];
        float right = bounds[idx * 2 + 1];
        float frac = Math.max(0f, Math.min(1f, (x - left) / Math.max(1f, right - left)));
        long targetMs = clip.trimStartMs + Math.round(frac * clip.getEffectiveDurationMs());

        boolean clipChanged = (idx != activeClipIndex);
        activeClipIndex = idx;
        activeClipPlayheadMs = targetMs;
        invalidate();

        if (listener != null) {
            if (clipChanged) listener.onClipSelected(idx);
            listener.onScrub(idx, targetMs, true);
        }
    }
}
