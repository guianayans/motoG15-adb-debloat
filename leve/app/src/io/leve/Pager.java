package io.leve;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

/** Full-width pages side by side, dragged sideways and snapped to the nearest one. */
final class Pager extends ViewGroup {
    interface Listener {
        void onScrolled(float position); // 0..pages-1, fractional while dragging
        void onPage(int page);
    }

    private final OverScroller scroller;
    private final int slop, minFling;
    private VelocityTracker velocity;
    private float downX, downY, lastX;
    private boolean dragging;
    private int page;
    private Listener listener;

    Pager(Context c) {
        super(c);
        scroller = new OverScroller(c, new DecelerateInterpolator(2.2f));
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity() * 4;
    }

    void setListener(Listener l) { listener = l; }
    int page() { return page; }

    void setPage(int p, boolean animate) {
        p = Math.max(0, Math.min(getChildCount() - 1, p));
        boolean changed = p != page;
        page = p;
        int target = p * getWidth();
        if (!animate || getWidth() == 0) {
            scroller.forceFinished(true);
            scrollTo(target, 0);
        } else {
            int dx = target - getScrollX();
            scroller.startScroll(getScrollX(), 0, dx, 0, Math.min(380, 160 + Math.abs(dx) / 4));
            postInvalidateOnAnimation();
        }
        if (changed && listener != null) listener.onPage(p);
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).layout(i * w, 0, (i + 1) * w, h);
        if (scroller.isFinished()) scrollTo(page * w, 0);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
        track(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = ev.getX();
                downY = ev.getY();
                dragging = !scroller.isFinished();
                if (dragging) scroller.forceFinished(true);
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(ev.getX() - downX), dy = Math.abs(ev.getY() - downY);
                if (!dragging && dx > slop && dx > dy * 1.2f) {
                    dragging = true;
                    lastX = ev.getX();
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
        }
        return dragging;
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        track(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = ev.getX();
                downY = ev.getY();
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) {
                    float dx = Math.abs(ev.getX() - downX), dy = Math.abs(ev.getY() - downY);
                    if (dx > slop && dx > dy * 1.2f) { dragging = true; lastX = ev.getX(); }
                    else return true;
                }
                float delta = lastX - ev.getX();
                lastX = ev.getX();
                int max = (getChildCount() - 1) * getWidth();
                int x = getScrollX();
                if (x + delta < 0 || x + delta > max) delta /= 3; // resistance past the ends
                scrollTo(Math.round(Math.max(-getWidth() / 4f, Math.min(max + getWidth() / 4f, x + delta))), 0);
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    velocity.computeCurrentVelocity(1000);
                    float vx = velocity.getXVelocity();
                    int target = Math.round(getScrollX() / (float) getWidth());
                    if (vx < -minFling) target = page + 1;
                    else if (vx > minFling) target = page - 1;
                    setPage(target, true);
                }
                // fall through
            case MotionEvent.ACTION_CANCEL:
                if (dragging && ev.getActionMasked() == MotionEvent.ACTION_CANCEL) setPage(page, true);
                dragging = false;
                if (velocity != null) { velocity.recycle(); velocity = null; }
                return true;
        }
        return true;
    }

    private void track(MotionEvent ev) {
        if (velocity == null) velocity = VelocityTracker.obtain();
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) velocity.clear();
        velocity.addMovement(ev);
    }

    boolean isDragging() { return dragging; }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            postInvalidateOnAnimation();
        }
    }

    @Override protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (listener != null && getWidth() > 0) listener.onScrolled(l / (float) getWidth());
    }
}
