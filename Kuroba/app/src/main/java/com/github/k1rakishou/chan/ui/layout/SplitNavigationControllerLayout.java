/*
 * KurobaEx - *chan browser https://github.com/K1rakishou/Kuroba-Experimental/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.k1rakishou.chan.ui.layout;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.dp;

import android.content.Context;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.github.k1rakishou.chan.R;
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils;
import com.github.k1rakishou.core_themes.ThemeEngine;
import com.github.k1rakishou.v2.KurobaSettings;

import javax.inject.Inject;

public class SplitNavigationControllerLayout extends LinearLayout implements ThemeEngine.ThemeChangesListener {
    // Below this percent the catalog snaps to fully hidden
    private static final int COLLAPSE_THRESHOLD_PERCENT = 10;
    private static final int MIN_CATALOG_WIDTH_PERCENT = 15;
    private static final int MAX_CATALOG_WIDTH_PERCENT = 60;
    private static final int DEFAULT_CATALOG_WIDTH_PERCENT = 35;

    @Inject
    ThemeEngine themeEngine;
    @Inject
    KurobaSettings kurobaSettings;

    private final int dividerWidth;
    private final int collapsedDividerWidth;
    private final int dividerTouchZone;
    private final int minimumLeftWidth;
    private final int touchSlop;

    private ViewGroup leftView;
    private ViewGroup rightView;
    private View divider;

    // Percent of the total width the catalog (left) pane takes. 0 == catalog hidden.
    private float catalogWidthPercent;
    // Width to come back to when the collapsed divider is tapped
    private float lastExpandedPercent;
    private boolean draggingDivider = false;
    private boolean dragCandidate = false;
    private float downX = 0f;
    private float downY = 0f;

    public SplitNavigationControllerLayout(Context context) {
        this(context, null);
    }

    public SplitNavigationControllerLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SplitNavigationControllerLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        AppModuleAndroidUtils.extractActivityComponent(getContext())
                .inject(this);

        setOrientation(LinearLayout.HORIZONTAL);

        dividerWidth = dp(1);
        // Wide enough to be a comfortable tap target: while collapsed this strip is the only
        // way back to the catalog, since DrawerLayout owns drags near the left screen edge.
        collapsedDividerWidth = dp(12);
        dividerTouchZone = dp(24);
        minimumLeftWidth = dp(300);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        int storedPercent = kurobaSettings.getApplication()
                .getSplitLayoutCatalogWidthPercent()
                .readBlocking();

        // Never restore a fully collapsed catalog on a fresh start. A hidden catalog pane is a
        // runtime state only, otherwise a user who collapses it has no way back if anything goes
        // wrong on startup.
        catalogWidthPercent = storedPercent < MIN_CATALOG_WIDTH_PERCENT
                ? DEFAULT_CATALOG_WIDTH_PERCENT
                : storedPercent;
        lastExpandedPercent = catalogWidthPercent;
    }

    private void applyCollapsedState() {
        if (leftView == null) {
            return;
        }

        boolean collapsed = isCollapsed();

        // GONE rather than a 0px wide view: a zero width but still laid out pane makes the
        // catalog bind its posts with width == 0, which blows up in ThreadCellData.
        leftView.setVisibility(collapsed ? GONE : VISIBLE);

        if (divider != null) {
            // Only tappable while collapsed, so that an expanded divider does not swallow
            // touches meant for the panes on either side of it.
            divider.setClickable(collapsed);
            divider.setFocusable(collapsed);
            divider.setContentDescription(collapsed
                    ? getContext().getString(R.string.split_layout_restore_catalog)
                    : null);
        }

        updateDividerColor();
    }

    private void updateDividerColor() {
        if (divider == null) {
            return;
        }

        // While collapsed the strip is an interactive control rather than a separator, so make
        // it stand out instead of blending into the background like a hairline divider does.
        divider.setBackgroundColor(isCollapsed()
                ? themeEngine.getChanTheme().getAccentColor()
                : themeEngine.getChanTheme().getDividerColor());
    }

    /**
     * Restores the catalog pane to the width it had before it was collapsed.
     *
     * <p>This is driven by a click listener rather than by our own drag handling on purpose:
     * androidx {@link androidx.drawerlayout.widget.DrawerLayout} is an ancestor of this view and
     * its ViewDragHelper claims horizontal drags within 20dp of the left screen edge before this
     * layout ever sees them. A collapsed divider sits inside that zone, so a drag can never be
     * used to bring the catalog back. Taps are not affected by ViewDragHelper, which leaves the
     * drawer's edge swipe working exactly as before.
     * */
    private void expandCatalogFromCollapsed() {
        if (!isCollapsed()) {
            return;
        }

        catalogWidthPercent = Math.max(MIN_CATALOG_WIDTH_PERCENT,
                Math.min(lastExpandedPercent, MAX_CATALOG_WIDTH_PERCENT));

        kurobaSettings.getApplication()
                .getSplitLayoutCatalogWidthPercent()
                .writeAsync((int) catalogWidthPercent);

        applyCollapsedState();
        requestLayout();

        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        themeEngine.addListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        themeEngine.removeListener(this);
    }

    @Override
    public void onThemeChanged() {
        updateDividerColor();
    }

    public void setLeftView(ViewGroup leftView) {
        this.leftView = leftView;
    }

    public void setRightView(ViewGroup rightView) {
        this.rightView = rightView;
    }

    public void setDivider(View divider) {
        this.divider = divider;
    }

    public void build() {
        addView(leftView, new LinearLayout.LayoutParams(0, MATCH_PARENT));
        addView(divider, new LinearLayout.LayoutParams(dividerWidth, MATCH_PARENT));
        addView(rightView, new LinearLayout.LayoutParams(0, MATCH_PARENT));

        divider.setOnClickListener(v -> expandCatalogFromCollapsed());

        applyCollapsedState();
        onThemeChanged();
    }

    private boolean isCollapsed() {
        return catalogWidthPercent < COLLAPSE_THRESHOLD_PERCENT;
    }

    private int currentDividerWidth() {
        return isCollapsed() ? collapsedDividerWidth : dividerWidth;
    }

    private int calculateLeftWidth(int widthSize) {
        if (draggingDivider) {
            // While dragging, follow the finger directly (all the way down to 0)
            return (int) (widthSize * catalogWidthPercent / 100f);
        }

        if (isCollapsed()) {
            return 0;
        }

        int minWidth = Math.min(minimumLeftWidth, widthSize / 2);
        int leftWidth = Math.max(minWidth, (int) (widthSize * catalogWidthPercent / 100f));
        return Math.min(leftWidth, (int) (widthSize * (MAX_CATALOG_WIDTH_PERCENT / 100f)));
    }

    private float dividerCenterX() {
        int widthSize = getWidth();
        if (widthSize <= 0) {
            return 0f;
        }

        return calculateLeftWidth(widthSize) + (currentDividerWidth() / 2f);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float distanceToDivider = Math.abs(event.getX() - dividerCenterX());
                dragCandidate = distanceToDivider <= dividerTouchZone;
                downX = event.getX();
                downY = event.getY();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!dragCandidate || draggingDivider) {
                    break;
                }

                float dx = Math.abs(event.getX() - downX);
                float dy = Math.abs(event.getY() - downY);

                // Claim the gesture only when it's clearly a horizontal drag so that
                // vertical scrolling of the underlying lists keeps working
                if (dx > touchSlop && dx > (dy * 1.5f)) {
                    startDividerDrag();
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                dragCandidate = false;
                break;
            }
        }

        return super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!draggingDivider) {
            return super.onTouchEvent(event);
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                int widthSize = getWidth();
                if (widthSize <= 0) {
                    break;
                }

                float newPercent = (event.getX() / (float) widthSize) * 100f;
                catalogWidthPercent = Math.max(0f, Math.min(newPercent, MAX_CATALOG_WIDTH_PERCENT));
                requestLayout();
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                finishDividerDrag();
                break;
            }
        }

        return true;
    }

    private void startDividerDrag() {
        draggingDivider = true;
        dragCandidate = false;

        // Make the pane visible again so it can follow the finger back out of the collapsed state
        if (leftView != null) {
            leftView.setVisibility(VISIBLE);
        }

        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }

        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void finishDividerDrag() {
        draggingDivider = false;

        if (catalogWidthPercent < COLLAPSE_THRESHOLD_PERCENT) {
            // Snap to fully hidden catalog. lastExpandedPercent is deliberately left alone so a
            // later tap on the collapsed strip comes back to the width the user last chose.
            catalogWidthPercent = 0f;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        } else {
            catalogWidthPercent = Math.max(MIN_CATALOG_WIDTH_PERCENT,
                    Math.min(catalogWidthPercent, MAX_CATALOG_WIDTH_PERCENT));
            lastExpandedPercent = catalogWidthPercent;
        }

        kurobaSettings.getApplication()
                .getSplitLayoutCatalogWidthPercent()
                .writeAsync((int) catalogWidthPercent);

        applyCollapsedState();
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int currentDividerWidth = currentDividerWidth();
        int leftWidth = calculateLeftWidth(widthSize);
        int rightWidth = widthSize - currentDividerWidth - leftWidth;

        leftView.getLayoutParams().width = leftWidth;
        divider.getLayoutParams().width = currentDividerWidth;
        rightView.getLayoutParams().width = rightWidth;

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
