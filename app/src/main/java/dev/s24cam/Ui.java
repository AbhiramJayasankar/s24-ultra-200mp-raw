package dev.s24cam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Palette, type and the three custom views of the camera screen. */
final class Ui {
    static final int BLACK = 0xFF000000;
    static final int BONE = 0xFFECE8DF;
    static final int SLATE = 0xFF8C9399;
    static final int GOLD = 0xFFD9A441;
    static final int ERROR = 0xFFE8806A;
    // Muted filter colours for the colour-filter glyph.
    static final int CFA_R = 0xFFB4554A, CFA_G = 0xFF5E8A5A, CFA_B = 0xFF4E6E9E;

    static final Typeface FIGURES = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
    static final Typeface TEXT = Typeface.create("sans-serif", Typeface.NORMAL);

    private Ui() {}

    static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    /** Shutter: bone ring and disc; while a DNG is being written a gold arc shows progress. */
    static final class ShutterView extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private int progress = -1;

        ShutterView(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(c, 3));
            ring.setColor(BONE);
            disc.setColor(BONE);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(dp(c, 3));
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(GOLD);
            setContentDescription("Take photo");
            setFocusable(true);
            setClickable(true);
        }

        /** -1 = idle, 0..100 = writing. */
        void setProgress(int p) {
            progress = p;
            setEnabled(p < 0);
            setContentDescription(p < 0 ? "Take photo" : "Saving, " + p + " percent");
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight());
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = s / 2f - ring.getStrokeWidth();
            box.set(cx - r, cy - r, cx + r, cy + r);
            if (progress < 0) {
                ring.setAlpha(255);
                c.drawCircle(cx, cy, r, ring);
                disc.setAlpha(isPressed() ? 170 : 255);
                c.drawCircle(cx, cy, r - dp(getContext(), 7), disc);
            } else {
                ring.setAlpha(60);
                c.drawCircle(cx, cy, r, ring);
                c.drawArc(box, -90, 360f * progress / 100f, false, arc);
                disc.setAlpha(40);
                c.drawCircle(cx, cy, r - dp(getContext(), 7), disc);
            }
            if (isFocused()) {
                ring.setAlpha(255);
                c.drawCircle(cx, cy, r + dp(getContext(), 4), ring);
            }
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }
    }

    /** 8x8 patch of the sensor's colour filter: 2x2 Bayer, or 4x4 blocks for the 16-cell mode. */
    static final class CfaGlyph extends View {
        private final Paint p = new Paint();
        private final int cell;

        CfaGlyph(Context c, int cell) {
            super(c);
            this.cell = cell;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas c) {
            float step = Math.min(getWidth(), getHeight()) / 8f;
            float gap = Math.max(1, step * 0.12f);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int by = (y / cell) & 1, bx = (x / cell) & 1;
                    p.setColor(by == bx ? CFA_G : by == 0 ? CFA_B : CFA_R);
                    c.drawRect(x * step, y * step, (x + 1) * step - gap, (y + 1) * step - gap, p);
                }
            }
        }
    }

    /** One mode in the selector: big megapixel figure, caption and colour-filter glyph. */
    static final class ModeTile extends LinearLayout {
        final Mode mode;
        private final TextView figure, caption;

        ModeTile(Context c, Mode mode) {
            super(c);
            this.mode = mode;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            int pad = (int) dp(c, 8);
            setPadding(pad, pad, pad, pad);
            setFocusable(true);
            setClickable(true);
            setContentDescription(mode.figure + " megapixel, " + mode.caption);

            LinearLayout top = new LinearLayout(c);
            top.setGravity(Gravity.BOTTOM);
            figure = new TextView(c);
            figure.setTypeface(FIGURES);
            figure.setTextSize(40);
            figure.setIncludeFontPadding(false);
            figure.setText(mode.figure);
            top.addView(figure);
            CfaGlyph glyph = new CfaGlyph(c, mode.sixteenCell ? 4 : 1);
            int g = (int) dp(c, 14);
            LayoutParams gl = new LayoutParams(g, g);
            gl.leftMargin = (int) dp(c, 6);
            gl.bottomMargin = (int) dp(c, 6);
            top.addView(glyph, gl);
            addView(top);

            caption = new TextView(c);
            caption.setTypeface(TEXT);
            caption.setTextSize(13);
            caption.setText(mode.caption);
            addView(caption);
            setSelected(false);
        }

        @Override public void setSelected(boolean selected) {
            super.setSelected(selected);
            figure.setTextColor(selected ? GOLD : BONE);
            figure.setAlpha(selected ? 1f : 0.55f);
            caption.setTextColor(selected ? BONE : SLATE);
        }
    }
    /**
     * One exposure setting over the viewfinder. Drag sideways to change it in third
     * stops (right is brighter), which also makes it manual; tap to switch between
     * auto and manual. Gold means manual, like the torch label.
     */
    static final class ExposureDial extends LinearLayout {
        interface Listener { void onStep(int steps); void onTap(); }
        private final TextView value, caption;
        private final float step;
        private Listener listener;
        private float downX;
        private int taken;
        private boolean dragging;

        ExposureDial(Context c, String name) {
            super(c);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setFocusable(true);
            setClickable(true);
            step = dp(c, 22);
            value = new TextView(c);
            value.setTypeface(FIGURES);
            value.setTextSize(20);
            value.setIncludeFontPadding(false);
            value.setGravity(Gravity.CENTER);
            addView(value);
            caption = new TextView(c);
            caption.setTypeface(TEXT);
            caption.setTextSize(12);
            caption.setGravity(Gravity.CENTER);
            addView(caption);
            setContentDescription(name);
        }

        void setListener(Listener l) { listener = l; }

        void show(String text, String label, boolean manual) {
            value.setText(text);
            caption.setText(label);
            value.setTextColor(manual ? GOLD : BONE);
            caption.setTextColor(manual ? GOLD : SLATE);
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getX(); taken = 0; dragging = false;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    int steps = (int) ((e.getX() - downX) / step);
                    if (steps != taken) {
                        dragging = true;
                        if (listener != null) listener.onStep(steps - taken);
                        taken = steps;
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    if (!dragging && listener != null) { performClick(); listener.onTap(); }
                    return true;
                default:
                    return true;
            }
        }

        @Override public boolean performClick() { return super.performClick(); }
    }
}
