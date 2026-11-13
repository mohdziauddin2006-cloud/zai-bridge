package com.ziauddin.zaibridge;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Keeps its single child at a fixed aspect ratio and centres it, so legacy 4:3
 * and 16:9 games are letterboxed instead of stretched across a 20:9 panel.
 */
public class AspectRatioFrameLayout extends FrameLayout {

    private float aspect = 16f / 9f;

    public AspectRatioFrameLayout(Context context) {
        super(context);
    }

    public AspectRatioFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setAspectRatio(float widthOverHeight) {
        if (widthOverHeight <= 0f) return;
        aspect = widthOverHeight;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int height = MeasureSpec.getSize(heightMeasureSpec);

        int childWidth = width;
        int childHeight = Math.round(width / aspect);
        if (childHeight > height) {
            childHeight = height;
            childWidth = Math.round(height * aspect);
        }

        final int widthSpec = MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY);
        final int heightSpec = MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.measure(widthSpec, heightSpec);
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int parentWidth = right - left;
        final int parentHeight = bottom - top;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            final int childWidth = child.getMeasuredWidth();
            final int childHeight = child.getMeasuredHeight();
            final int childLeft = (parentWidth - childWidth) / 2;
            final int childTop = (parentHeight - childHeight) / 2;
            child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
        }
    }
}
