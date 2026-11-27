package com.mp3master.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VideoFilmstripView extends View {

    public interface OnFilmstripListener {
        void onClipSelected(int clipIndex);
        void onScrub(int clipIndex, long clipPositionMs, boolean fromUser);
        void onClipSwipedDelete(int clipIndex);
        void onTouchFinished();
    }

    private static final int GESTURE_NONE = 0;
    private static final int GESTURE_SCRUB = 1;
    private static final int GESTURE_SWIPE_DELETE = 2;

    private final List<AudioUtils.VideoInfo> clips = new ArrayList<>();
    private final Map<String, List<Bitmap>> frameCache = new HashMap<>();
    private int activeClipIndex = 0;
    private long activeClipPlayheadMs = 0L;

    private OnFilmstripListener listener;

    private float density;
    private float dividerGapPx;
    private float touchSlop;
    private float deleteThresholdPx;

    private int gestureMode = GESTURE_NONE;
    private int touchedClipIndex = -1;
    private float downX, downY;
    private float swipeOffsetY = 0f;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clipBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activeBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint deleteTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();
    private final RectF clipRect = new RectF();

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
        dividerGapPx = 6f * density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        deleteThresholdPx = 42f * density;

        bgPaint.setColor(ContextCompat.getColor(context, R.color.surface_bg));
        clipBgPaint.setColor(ContextCompat.getColor(context, R.color.pill_bg));
        dividerPaint.setColor(ContextCompat.getColor(context, R.color.surface_bg));

        activeBorderPaint.setColor(ContextCompat.getColor(context, R.color.primary));
        activeBorderPaint.setStyle(Paint.Style.STROKE);
        activeBorderPaint.setStrokeWidth(3f * density);

        badgeBgPaint.setColor(Color.parseColor("#B8000000"));
        badgeTextPaint.setColor(Color.WHITE);
        badgeTextPaint.setTextSize(10f * density);
        badgeTextPaint.setFakeBoldText(true);

        playheadPaint.setColor(Color.WHITE);
        playheadPaint.setStrokeWidth(2.5f * density);

        playheadShadowPaint.setColor(Color.parseColor("#99000000"));
        playheadShadowPaint.setStrokeWidth(5f * density);

        deleteTintPaint.setColor(Color.parseColor("#88DC2626"));
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
        this.swipeOffsetY = 0f;
        this.gestureMode = GESTURE_NONE;
        this.touchedClipIndex = -1;
        invalidate();
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

    private long getTotalEffectiveMs() {
        long sum = 0;
        for (AudioUtils.VideoInfo c : clips) {
            sum += Math.max(300L, c.getEffectiveDurationMs());
        }
        return Math.max(300L, sum);
    }

    private float[] computeClipBoundsX() {
        int n = clips.size();
        float[] bounds = new float[n * 2];
        if (n == 0) return bounds;
        float padX = 4f * density;
        float totalGaps = Math.max(0, n - 1) * dividerGapPx;
        float availW = Math.max(20f, getWidth() - padX * 2f - totalGaps);
        long totalMs = getTotalEffectiveMs();

        float curX = padX;
        for (int i = 0; i < n; i++) {
            long dur = Math.max(300L, clips.get(i).getEffectiveDurationMs());
            float w = availW * ((float) dur / (float) totalMs);
            bounds[i * 2] = curX;
            bounds[i * 2 + 1] = curX + w;
            curX += w + dividerGapPx;
        }
        return bounds;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || clips.isEmpty()) return;

        float topBase = 6f * density;
        float bottomBase = h - 6f * density;
        float[] bounds = computeClipBoundsX();

        for (int i = 0; i < clips.size(); i++) {
            AudioUtils.VideoInfo clip = clips.get(i);
            float left = bounds[i * 2];
            float right = bounds[i * 2 + 1];
            float segW = right - left;
            if (segW < 2f) continue;

            float dy = (gestureMode == GESTURE_SWIPE_DELETE && i == touchedClipIndex) ? swipeOffsetY : 0f;
            float top = topBase + dy;
            float bottom = bottomBase + dy;

            clipRect.set(left, top, right, bottom);
            canvas.save();
            canvas.clipRect(clipRect);

            canvas.drawRect(clipRect, clipBgPaint);

            // Draw real frames proportional to this clip's trim range
            List<Bitmap> allFrames = frameCache.get(clip.uri.toString());
            if (allFrames != null && !allFrames.isEmpty()) {
                int totalFrames = allFrames.size();
                float startFrac = clip.durationMs > 0 ? ((float) clip.trimStartMs / clip.durationMs) : 0f;
                float endFrac = clip.durationMs > 0 ? ((float) clip.trimEndMs / clip.durationMs) : 1f;
                int fStart = Math.max(0, Math.min(totalFrames - 1, Math.round(startFrac * (totalFrames - 1))));
                int fEnd = Math.max(fStart, Math.min(totalFrames - 1, Math.round(endFrac * (totalFrames - 1))));

                int tileCount = Math.max(1, Math.min(12, Math.round(segW / (44f * density))));
                float tileW = segW / tileCount;
                for (int t = 0; t < tileCount; t++) {
                    float fRatio = (tileCount == 1) ? 0.5f : ((float) t / (tileCount - 1));
                    int frameIdx = Math.round(fStart + fRatio * (fEnd - fStart));
                    frameIdx = Math.max(0, Math.min(totalFrames - 1, frameIdx));
                    Bitmap bmp = allFrames.get(frameIdx);
                    if (bmp != null && !bmp.isRecycled()) {
                        srcRect.set(0, 0, bmp.getWidth(), bmp.getHeight());
                        dstRect.set(left + t * tileW, top, left + (t + 1) * tileW + 0.5f, bottom);
                        canvas.drawBitmap(bmp, srcRect, dstRect, null);
                    }
                }
            }

            // Red tint when swiping to delete past threshold
            if (gestureMode == GESTURE_SWIPE_DELETE && i == touchedClipIndex && Math.abs(swipeOffsetY) > deleteThresholdPx * 0.5f) {
                canvas.drawRect(clipRect, deleteTintPaint);
            }

            // Clip index & duration badge
            if (segW > 28f * density) {
                String label = (i + 1) + " • " + AudioUtils.formatTimeMs(clip.getEffectiveDurationMs());
                float tw = badgeTextPaint.measureText(label);
                float bx = left + 4f * density;
                float by = bottom - 4f * density;
                canvas.drawRect(bx - 2f * density, by - 12f * density, bx + tw + 3f * density, by + 2f * density, badgeBgPaint);
                canvas.drawText(label, bx, by - 2f * density, badgeTextPaint);
            }

            canvas.restore();

            // Highlight border for active clip
            if (i == activeClipIndex) {
                canvas.drawRect(left + 1.5f * density, top + 1.5f * density, right - 1.5f * density, bottom - 1.5f * density, activeBorderPaint);
            }
        }

        // White vertical playhead on active clip
        if (activeClipIndex >= 0 && activeClipIndex < clips.size()) {
            AudioUtils.VideoInfo active = clips.get(activeClipIndex);
            float left = bounds[activeClipIndex * 2];
            float right = bounds[activeClipIndex * 2 + 1];
            long effDur = Math.max(1L, active.getEffectiveDurationMs());
            float frac = Math.max(0f, Math.min(1f, (float) (activeClipPlayheadMs - active.trimStartMs) / (float) effDur));
            float playX = left + (right - left) * frac;

            canvas.drawLine(playX, 1f * density, playX, h - 1f * density, playheadShadowPaint);
            canvas.drawLine(playX, 1f * density, playX, h - 1f * density, playheadPaint);
            canvas.drawCircle(playX, 4f * density, 4f * density, playheadPaint);
        }
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

        float x = event.getX();
        float y = event.getY();
        float[] bounds = computeClipBoundsX();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                swipeOffsetY = 0f;
                gestureMode = GESTURE_NONE;
                touchedClipIndex = findClipAtX(x, bounds);
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = x - downX;
                float dy = y - downY;

                if (gestureMode == GESTURE_NONE) {
                    if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx) * 1.2f) {
                        gestureMode = GESTURE_SWIPE_DELETE;
                    } else if (Math.abs(dx) > touchSlop) {
                        gestureMode = GESTURE_SCRUB;
                    }
                }

                if (gestureMode == GESTURE_SWIPE_DELETE) {
                    swipeOffsetY = dy;
                    invalidate();
                } else if (gestureMode == GESTURE_SCRUB) {
                    scrubAtX(x, bounds);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
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
                } else if (gestureMode == GESTURE_NONE) {
                    // Single tap -> select clip & seek playhead right there
                    scrubAtX(x, bounds);
                    if (listener != null) listener.onTouchFinished();
                } else {
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
