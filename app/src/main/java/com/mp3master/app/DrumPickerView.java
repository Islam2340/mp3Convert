package com.mp3master.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

public class DrumPickerView extends View {

    public interface OnValueChangedListener {
        void onValueChanged(int index, String label);
    }

    private String[] items = new String[]{"128 кбит/с"};
    private int selectedIndex = 0;
    private float scrollOffset = 0f; // in item units (0 = index 0, 1 = index 1, ...)
    private int lastHapticIndex = 0;

    private float density;
    private float itemHeightPx;
    private float touchSlop;
    private float downY;
    private float lastY;
    private boolean isDragging = false;

    private ValueAnimator snapAnimator;
    private OnValueChangedListener listener;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerBandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint normalTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bandRect = new RectF();
    private final RectF bgRect = new RectF();

    public DrumPickerView(Context context) {
        super(context);
        init(context);
    }

    public DrumPickerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public DrumPickerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        density = getResources().getDisplayMetrics().density;
        itemHeightPx = 42f * density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        int primary = ContextCompat.getColor(context, R.color.primary);
        int primaryContainer = ContextCompat.getColor(context, R.color.primary_container);
        int surfaceBg = ContextCompat.getColor(context, R.color.surface_bg);
        int textSec = ContextCompat.getColor(context, R.color.text_secondary);

        bgPaint.setColor(surfaceBg);

        centerBandPaint.setColor(primaryContainer);
        centerBandPaint.setAlpha(185);

        centerBorderPaint.setColor(primary);
        centerBorderPaint.setAlpha(55);
        centerBorderPaint.setStyle(Paint.Style.STROKE);
        centerBorderPaint.setStrokeWidth(1.2f * density);

        selectedTextPaint.setColor(primary);
        selectedTextPaint.setTextSize(17f * density);
        selectedTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        selectedTextPaint.setTextAlign(Paint.Align.CENTER);

        normalTextPaint.setColor(textSec);
        normalTextPaint.setTextSize(14.5f * density);
        normalTextPaint.setTypeface(Typeface.DEFAULT);
        normalTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setItems(String[] labels, int initialIndex) {
        if (labels == null || labels.length == 0) return;
        this.items = labels;
        this.selectedIndex = Math.max(0, Math.min(initialIndex, labels.length - 1));
        this.scrollOffset = this.selectedIndex;
        this.lastHapticIndex = this.selectedIndex;
        invalidate();
    }

    public void setSelectedIndex(int index, boolean notify) {
        if (items == null || items.length == 0) return;
        int clamped = Math.max(0, Math.min(index, items.length - 1));
        selectedIndex = clamped;
        scrollOffset = clamped;
        lastHapticIndex = clamped;
        invalidate();
        if (notify && listener != null) {
            listener.onValueChanged(selectedIndex, items[selectedIndex]);
        }
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setOnValueChangedListener(OnValueChangedListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredH = Math.round(itemHeightPx * 3.1f);
        int h = resolveSize(desiredH, heightMeasureSpec);
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || items == null || items.length == 0) return;

        float radius = 14f * density;
        bgRect.set(0, 0, w, h);
        canvas.drawRoundRect(bgRect, radius, radius, bgPaint);

        float centerY = h / 2f;
        float bandHalf = itemHeightPx / 2f;
        bandRect.set(8f * density, centerY - bandHalf, w - 8f * density, centerY + bandHalf);
        canvas.drawRoundRect(bandRect, 11f * density, 11f * density, centerBandPaint);
        canvas.drawRoundRect(bandRect, 11f * density, 11f * density, centerBorderPaint);

        float cx = w / 2f;
        for (int i = 0; i < items.length; i++) {
            float diff = i - scrollOffset;
            if (Math.abs(diff) > 2.2f) continue;

            float y = centerY + diff * itemHeightPx;
            float absDiff = Math.abs(diff);

            Paint paint = absDiff < 0.45f ? selectedTextPaint : normalTextPaint;
            float alphaFrac = Math.max(0.18f, 1f - absDiff * 0.55f);
            paint.setAlpha(Math.round(alphaFrac * 255));

            Paint.FontMetrics fm = paint.getFontMetrics();
            float baseline = y - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(items[i], cx, baseline, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (items == null || items.length <= 1) return false;

        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (snapAnimator != null && snapAnimator.isRunning()) {
                    snapAnimator.cancel();
                }
                downY = event.getY();
                lastY = downY;
                isDragging = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                float y = event.getY();
                if (!isDragging && Math.abs(y - downY) > touchSlop) {
                    isDragging = true;
                }
                if (isDragging) {
                    float dy = y - lastY;
                    scrollOffset -= dy / itemHeightPx;
                    scrollOffset = Math.max(-0.25f, Math.min(items.length - 1 + 0.25f, scrollOffset));
                    int nearest = clampIndex(Math.round(scrollOffset));
                    if (nearest != lastHapticIndex) {
                        lastHapticIndex = nearest;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    invalidate();
                }
                lastY = y;
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!isDragging) {
                    // Tap upper third -> step up, lower third -> step down
                    float tapY = event.getY();
                    int target = selectedIndex;
                    if (tapY < getHeight() * 0.34f && selectedIndex > 0) {
                        target = selectedIndex - 1;
                    } else if (tapY > getHeight() * 0.66f && selectedIndex < items.length - 1) {
                        target = selectedIndex + 1;
                    }
                    if (target != selectedIndex) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    animateToIndex(target);
                } else {
                    int target = clampIndex(Math.round(scrollOffset));
                    animateToIndex(target);
                }
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    private int clampIndex(int idx) {
        return Math.max(0, Math.min(idx, items.length - 1));
    }

    private void animateToIndex(int targetIndex) {
        int clamped = clampIndex(targetIndex);
        boolean changed = (clamped != selectedIndex);
        selectedIndex = clamped;
        lastHapticIndex = clamped;

        if (snapAnimator != null) snapAnimator.cancel();
        snapAnimator = ValueAnimator.ofFloat(scrollOffset, clamped);
        snapAnimator.setDuration(140);
        snapAnimator.setInterpolator(new DecelerateInterpolator());
        snapAnimator.addUpdateListener(anim -> {
            scrollOffset = (float) anim.getAnimatedValue();
            invalidate();
        });
        snapAnimator.start();

        if (changed && listener != null) {
            listener.onValueChanged(selectedIndex, items[selectedIndex]);
        }
    }
}
