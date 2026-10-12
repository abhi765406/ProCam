package com.procam.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.View;

/** All the small drawing-only views that sit on top of the viewfinder. */
final class Overlays {

    private Overlays() {}

    // ------------------------------------------------------------ grid

    static final class Grid extends View {
        int mode = 1; // 0 off, 1 thirds, 2 golden ratio, 3 diagonals + cross
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Grid(Context c) {
            super(c);
            p.setColor(Color.argb(150, 255, 255, 255));
            p.setStrokeWidth(Math.max(1f, c.getResources().getDisplayMetrics().density * 0.8f));
        }

        @Override
        protected void onDraw(Canvas c) {
            if (mode == 0) return;
            float w = getWidth(), h = getHeight();
            if (mode == 1) {
                for (int i = 1; i < 3; i++) {
                    c.drawLine(w * i / 3f, 0, w * i / 3f, h, p);
                    c.drawLine(0, h * i / 3f, w, h * i / 3f, p);
                }
            } else if (mode == 2) {
                float a = 0.382f, b = 0.618f;
                c.drawLine(w * a, 0, w * a, h, p);
                c.drawLine(w * b, 0, w * b, h, p);
                c.drawLine(0, h * a, w, h * a, p);
                c.drawLine(0, h * b, w, h * b, p);
            } else {
                c.drawLine(0, 0, w, h, p);
                c.drawLine(w, 0, 0, h, p);
                c.drawLine(w / 2f, 0, w / 2f, h, p);
                c.drawLine(0, h / 2f, w, h / 2f, p);
            }
        }
    }

    // ------------------------------------------------------------ level (horizon)

    static final class Level extends View implements SensorEventListener {
        boolean enabled = true;
        private float roll = 0f;
        private SensorManager sm;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Level(Context c) {
            super(c);
            sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
            p.setStrokeWidth(c.getResources().getDisplayMetrics().density * 2f);
        }

        void start() {
            if (sm == null) return;
            Sensor s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI);
        }

        void stop() {
            if (sm != null) sm.unregisterListener(this);
        }

        @Override
        public void onSensorChanged(SensorEvent e) {
            float x = e.values[0], y = e.values[1];
            float target = (float) Math.toDegrees(Math.atan2(x, y));
            roll += (target - roll) * 0.25f; // smooth
            invalidate();
        }

        @Override
        public void onAccuracyChanged(Sensor s, int a) {
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!enabled) return;
            float w = getWidth(), h = getHeight();
            float len = w * 0.22f;
            boolean level = Math.abs(roll) < 1.0f;
            p.setColor(level ? Color.rgb(255, 214, 10) : Color.argb(170, 255, 255, 255));
            c.save();
            c.rotate(-roll, w / 2f, h / 2f);
            c.drawLine(w / 2f - len, h / 2f, w / 2f - len * 0.35f, h / 2f, p);
            c.drawLine(w / 2f + len * 0.35f, h / 2f, w / 2f + len, h / 2f, p);
            c.restore();
            if (level) {
                c.drawLine(w / 2f - len, h / 2f, w / 2f + len, h / 2f, p);
            }
        }
    }

    // ------------------------------------------------------------ histogram

    static final class Histogram extends View {
        private int[] data = new int[64];
        private final Paint p = new Paint();
        private final Paint bg = new Paint();

        Histogram(Context c) {
            super(c);
            p.setColor(Color.argb(210, 255, 255, 255));
            bg.setColor(Color.argb(90, 0, 0, 0));
        }

        void set(int[] d) {
            data = d;
            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawRect(0, 0, w, h, bg);
            int max = 1;
            for (int i = 1; i < data.length - 1; i++) max = Math.max(max, data[i]);
            float bw = w / data.length;
            for (int i = 0; i < data.length; i++) {
                float v = Math.min(1f, data[i] / (float) max);
                c.drawRect(i * bw, h - v * h, (i + 1) * bw, h, p);
            }
        }
    }

    // ------------------------------------------------------------ focus ring

    static final class FocusRing extends View {
        private float x, y;
        private long t0 = 0;
        private boolean locked;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        FocusRing(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(c.getResources().getDisplayMetrics().density * 1.6f);
        }

        void show(float px, float py, boolean lock) {
            x = px;
            y = py;
            locked = lock;
            t0 = System.currentTimeMillis();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (t0 == 0) return;
            long dt = System.currentTimeMillis() - t0;
            long life = locked ? 2400 : 1500;
            if (dt > life) {
                t0 = 0;
                return;
            }
            float d = getResources().getDisplayMetrics().density;
            float r = 38f * d * (dt < 200 ? 1.5f - 0.5f * (dt / 200f) : 1f);
            int alpha = dt > life - 400 ? (int) (255 * (life - dt) / 400f) : 255;
            p.setColor(locked ? Color.argb(alpha, 255, 214, 10) : Color.argb(alpha, 255, 255, 255));
            c.drawCircle(x, y, r, p);
            c.drawLine(x - r - 6 * d, y, x - r + 8 * d, y, p);
            c.drawLine(x + r - 8 * d, y, x + r + 6 * d, y, p);
            postInvalidateOnAnimation();
        }
    }

    // ------------------------------------------------------------ crop / cinema guides

    static final class Guide extends View {
        int mode = 0; // 0 nothing, 1 square 1:1 mask, 2 cinema 2.39:1 bars
        private final Paint dim = new Paint();
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

        Guide(Context c) {
            super(c);
            dim.setColor(Color.argb(190, 0, 0, 0));
            line.setColor(Color.argb(200, 255, 255, 255));
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(c.getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (mode == 1) {
                float s = Math.min(w, h);
                float top = (h - s) / 2f, left = (w - s) / 2f;
                c.drawRect(0, 0, w, top, dim);
                c.drawRect(0, top + s, w, h, dim);
                c.drawRect(0, top, left, top + s, dim);
                c.drawRect(left + s, top, w, top + s, dim);
                c.drawRect(new RectF(left, top, left + s, top + s), line);
            } else if (mode == 2) {
                float vis = w / 2.39f; // visible height of a 2.39:1 frame at this width
                if (vis < h) {
                    float top = (h - vis) / 2f;
                    c.drawRect(0, 0, w, top, dim);
                    c.drawRect(0, top + vis, w, h, dim);
                    c.drawLine(0, top, w, top, line);
                    c.drawLine(0, top + vis, w, top + vis, line);
                }
            }
        }
    }

    // ------------------------------------------------------------ shutter

    static final class Shutter extends View {
        boolean video = false;
        boolean recording = false;
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

        Shutter(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(Color.WHITE);
            ring.setStrokeWidth(c.getResources().getDisplayMetrics().density * 4f);
        }

        void set(boolean v, boolean r) {
            video = v;
            recording = r;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float R = Math.min(w, h) / 2f - ring.getStrokeWidth();
            c.drawCircle(cx, cy, R, ring);
            if (recording) {
                fill.setColor(Color.rgb(255, 59, 48));
                float s = R * 0.5f;
                c.drawRoundRect(new RectF(cx - s, cy - s, cx + s, cy + s), s * 0.3f, s * 0.3f, fill);
            } else {
                fill.setColor(video ? Color.rgb(255, 59, 48) : Color.WHITE);
                c.drawCircle(cx, cy, R * 0.8f, fill);
            }
        }
    }
}
