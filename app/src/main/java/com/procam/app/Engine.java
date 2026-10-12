package com.procam.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.FaceDetector;

import java.util.ArrayList;
import java.util.List;

/**
 * Software "computational photography": local tone mapping (shadow lift and
 * highlight recovery), clarity, sharpening, denoise, skin smoothing, colour
 * styles and looks, portrait background blur.
 * Works in horizontal stripes so it stays light on memory.
 */
final class Engine {

    private Engine() {}

    static final class Params {
        int style = 0;
        int look = 0;
        float strength = 1f;     // scales the tone mapping
        float gamma = 1f;        // <1 brightens (night)
        float shadowBoost = 0f;  // extra shadow lift (night)
        float denoiseBoost = 0f; // extra denoise (night)
        float beauty = 0f;       // 0..1 skin smoothing
        boolean fast = false;    // cheaper path for weak phones
    }

    // ------------------------------------------------------------ decoding / geometry

    static Bitmap decode(byte[] data, int maxPixels) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        long px = (long) o.outWidth * o.outHeight;
        while (px / ((long) sample * sample) > maxPixels) sample *= 2;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                BitmapFactory.Options d = new BitmapFactory.Options();
                d.inSampleSize = sample;
                d.inPreferredConfig = Bitmap.Config.ARGB_8888;
                d.inMutable = true;
                Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, d);
                if (b != null) return b;
            } catch (OutOfMemoryError e) {
                // try again at half size
            }
            sample *= 2;
        }
        return null;
    }

    static Bitmap mutable(Bitmap b) {
        if (b.isMutable() && b.getConfig() == Bitmap.Config.ARGB_8888) return b;
        Bitmap c = b.copy(Bitmap.Config.ARGB_8888, true);
        b.recycle();
        return c;
    }

    /** Rotates (clockwise degrees), optionally mirrors, optionally crops to a square. */
    static Bitmap upright(Bitmap src, int rotation, boolean mirror, boolean square) {
        Bitmap cur = src;
        if (rotation % 360 != 0 || mirror) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            if (mirror) m.postScale(-1f, 1f);
            Bitmap r = Bitmap.createBitmap(cur, 0, 0, cur.getWidth(), cur.getHeight(), m, true);
            if (r != cur) cur.recycle();
            cur = r;
        }
        if (square) {
            int s = Math.min(cur.getWidth(), cur.getHeight());
            int x = (cur.getWidth() - s) / 2;
            int y = (cur.getHeight() - s) / 2;
            Bitmap c = Bitmap.createBitmap(cur, x, y, s, s);
            if (c != cur) cur.recycle();
            cur = c;
        }
        return mutable(cur);
    }

    // ------------------------------------------------------------ main finishing pass

    static void enhance(Bitmap b, Params p) {
        final int w = b.getWidth(), h = b.getHeight();
        Looks.Style st = Looks.STYLES[p.style];
        Looks.Look lk = Looks.look(p.style, p.look);
        final float[] cm = Looks.compose(p.style, p.look);

        final float k = p.strength;
        final float shadow = st.shadow * k + p.shadowBoost;
        final float highlight = st.highlight * k;
        final float clarity = st.clarity * k;
        final float sharpen = st.sharpen * k;
        final float denoise = Math.min(1f, st.denoise * k + p.denoiseBoost);
        final float beauty = p.beauty;
        final float vig = lk.vignette;
        final float grain = lk.grain;

        final boolean useMid = denoise > 0.02f || beauty > 0.01f;
        final boolean useFine = !p.fast && sharpen > 0.01f;

        // local brightness map (very low resolution, blurred)
        final int sw = Math.max(4, w / 24), sh = Math.max(4, h / 24);
        final float[] M = new float[sw * sh];
        {
            Bitmap s = Bitmap.createScaledBitmap(b, sw, sh, true);
            int[] sp = new int[sw * sh];
            s.getPixels(sp, 0, sw, 0, 0, sw, sh);
            if (s != b) s.recycle();
            for (int i = 0; i < sp.length; i++) {
                int c = sp[i];
                M[i] = (0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255)) / 255f;
            }
            boxBlurF(M, sw, sh, 2);
            boxBlurF(M, sw, sh, 2);
        }

        int mw = 2, mh = 2;
        int[] mid = null;
        if (useMid) {
            mw = Math.max(2, w / 4);
            mh = Math.max(2, h / 4);
            Bitmap s = Bitmap.createScaledBitmap(b, mw, mh, true);
            mid = new int[mw * mh];
            s.getPixels(mid, 0, mw, 0, 0, mw, mh);
            if (s != b) s.recycle();
        }
        int fw = 2, fh = 2;
        int[] fine = null;
        if (useFine) {
            fw = Math.max(2, w / 2);
            fh = Math.max(2, h / 2);
            Bitmap s = Bitmap.createScaledBitmap(b, fw, fh, true);
            fine = new int[fw * fh];
            s.getPixels(fine, 0, fw, 0, 0, fw, fh);
            if (s != b) s.recycle();
        }

        float[] gamma = null;
        if (Math.abs(p.gamma - 1f) > 0.01f) {
            gamma = new float[256];
            for (int i = 0; i < 256; i++) gamma[i] = (float) (255.0 * Math.pow(i / 255.0, p.gamma));
        }

        // horizontal sampling tables
        int[] mx0 = new int[w], mx1 = new int[w];
        float[] mfx = new float[w];
        int[] ax0 = new int[w], ax1 = new int[w];
        float[] afx = new float[w];
        int[] fx0 = new int[w], fx1 = new int[w];
        float[] ffx = new float[w];
        axis(w, sw, mx0, mx1, mfx);
        axis(w, mw, ax0, ax1, afx);
        axis(w, fw, fx0, fx1, ffx);

        final int STRIPE = 96;
        int[] px = new int[w * STRIPE];
        float cx = w / 2f, cy = h / 2f;
        int seed = 12345;

        for (int y0 = 0; y0 < h; y0 += STRIPE) {
            int rows = Math.min(STRIPE, h - y0);
            b.getPixels(px, 0, w, 0, y0, w, rows);
            for (int ry = 0; ry < rows; ry++) {
                int y = y0 + ry;
                // vertical taps
                float mv = (y + 0.5f) * sh / h - 0.5f;
                if (mv < 0) mv = 0;
                if (mv > sh - 1) mv = sh - 1;
                int my0 = (int) mv, my1 = Math.min(my0 + 1, sh - 1);
                float mfy = mv - my0;

                float av = (y + 0.5f) * mh / h - 0.5f;
                if (av < 0) av = 0;
                if (av > mh - 1) av = mh - 1;
                int ay0 = (int) av, ay1 = Math.min(ay0 + 1, mh - 1);
                float afy = av - ay0;

                float fv = (y + 0.5f) * fh / h - 0.5f;
                if (fv < 0) fv = 0;
                if (fv > fh - 1) fv = fh - 1;
                int fy0 = (int) fv, fy1 = Math.min(fy0 + 1, fh - 1);
                float ffy = fv - fy0;

                float dy = (y - cy) / cy;
                int row = ry * w;

                for (int x = 0; x < w; x++) {
                    int c = px[row + x];
                    float r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
                    float L0 = (0.299f * r + 0.587f * g + 0.114f * bl) * (1f / 255f);

                    // local mean brightness (bilinear)
                    float m00 = M[my0 * sw + mx0[x]], m01 = M[my0 * sw + mx1[x]];
                    float m10 = M[my1 * sw + mx0[x]], m11 = M[my1 * sw + mx1[x]];
                    float fx = mfx[x];
                    float mloc = (m00 + (m01 - m00) * fx) * (1f - mfy) + (m10 + (m11 - m10) * fx) * mfy;

                    float bw = 0f;
                    if (useMid) {
                        int i00 = mid[ay0 * mw + ax0[x]], i01 = mid[ay0 * mw + ax1[x]];
                        int i10 = mid[ay1 * mw + ax0[x]], i11 = mid[ay1 * mw + ax1[x]];
                        float gx = afx[x];
                        float mr = bil((i00 >> 16) & 255, (i01 >> 16) & 255, (i10 >> 16) & 255, (i11 >> 16) & 255, gx, afy);
                        float mg = bil((i00 >> 8) & 255, (i01 >> 8) & 255, (i10 >> 8) & 255, (i11 >> 8) & 255, gx, afy);
                        float mb = bil(i00 & 255, i01 & 255, i10 & 255, i11 & 255, gx, afy);
                        float dw = denoise * (1f - L0) * (1f - L0);
                        if (beauty > 0.01f) {
                            float skin = skinWeight(r, g, bl);
                            bw = beauty * skin;
                            if (bw > dw) dw = bw;
                        }
                        if (dw > 0f) {
                            r += (mr - r) * dw;
                            g += (mg - g) * dw;
                            bl += (mb - bl) * dw;
                        }
                    }

                    if (gamma != null) {
                        r = gamma[(int) clampf(r, 0, 255)];
                        g = gamma[(int) clampf(g, 0, 255)];
                        bl = gamma[(int) clampf(bl, 0, 255)];
                    }

                    // shadow lift / highlight recovery from the local brightness
                    float gain = 1f;
                    if (mloc < 0.5f) gain += shadow * (0.5f - mloc) * 2f * (1f - L0);
                    else gain -= highlight * (mloc - 0.5f) * 2f * L0;
                    if (gain < 0.6f) gain = 0.6f;
                    r *= gain;
                    g *= gain;
                    bl *= gain;

                    float keep = 1f - bw; // do not re-sharpen smoothed skin
                    // clarity (local contrast)
                    float det = clampf(L0 - mloc, -0.3f, 0.3f);
                    float add = clarity * det * 200f * keep;

                    // fine sharpening
                    if (useFine) {
                        int f00 = fine[fy0 * fw + fx0[x]], f01 = fine[fy0 * fw + fx1[x]];
                        int f10 = fine[fy1 * fw + fx0[x]], f11 = fine[fy1 * fw + fx1[x]];
                        float hx = ffx[x];
                        float fl = bil(lum(f00), lum(f01), lum(f10), lum(f11), hx, ffy) * (1f / 255f);
                        float fd = clampf(L0 - fl, -0.2f, 0.2f);
                        add += sharpen * fd * 255f * 1.2f * keep;
                    }
                    r += add;
                    g += add;
                    bl += add;

                    // colour style + look
                    float nr = cm[0] * r + cm[1] * g + cm[2] * bl + cm[4];
                    float ng = cm[5] * r + cm[6] * g + cm[7] * bl + cm[9];
                    float nb = cm[10] * r + cm[11] * g + cm[12] * bl + cm[14];

                    if (vig > 0f) {
                        float dx = (x - cx) / cx;
                        float vv = (dx * dx + dy * dy) * 0.5f;
                        float v = 1f - vig * vv * vv * 1.6f;
                        nr *= v;
                        ng *= v;
                        nb *= v;
                    }
                    if (grain > 0f) {
                        seed = seed * 1664525 + 1013904223;
                        float n = (((seed >>> 9) & 255) * (1f / 255f) - 0.5f) * grain * 255f;
                        nr += n;
                        ng += n;
                        nb += n;
                    }
                    px[row + x] = 0xff000000 | (cl(nr) << 16) | (cl(ng) << 8) | cl(nb);
                }
            }
            b.setPixels(px, 0, w, 0, y0, w, rows);
        }
    }

    // ------------------------------------------------------------ portrait

    static final int P_NATURAL = 0, P_STUDIO = 1, P_STAGE = 2, P_STAGE_MONO = 3, P_HIGH_KEY = 4;

    /** Simulated shallow depth of field: finds faces, keeps the subject sharp and blurs the rest. */
    static Bitmap portrait(Bitmap src, int effect, float blurAmount) {
        int w = src.getWidth(), h = src.getHeight();
        List<float[]> faces = findFaces(src);

        // 1. mask (low resolution, soft edges)
        int mw = Math.max(16, w / 8), mh = Math.max(16, h / 8);
        Bitmap mask = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888);
        Canvas mc = new Canvas(mask);
        Paint mp = new Paint(Paint.ANTI_ALIAS_FLAG);
        mp.setColor(Color.WHITE);
        mp.setMaskFilter(new BlurMaskFilter(Math.max(3f, mw * 0.035f), BlurMaskFilter.Blur.NORMAL));
        float sx = mw / (float) w, sy = mh / (float) h;
        if (faces.isEmpty()) {
            mc.drawOval(new RectF(mw * 0.18f, mh * 0.10f, mw * 0.82f, mh * 0.90f), mp);
        } else {
            for (float[] f : faces) { // f = {midX, midY, eyeDist} in image pixels
                float e = f[2];
                float hx = f[0] * sx, hy = (f[1] + e * 0.45f) * sy;
                mc.drawOval(new RectF(hx - e * 1.8f * sx, hy - e * 2.4f * sy, hx + e * 1.8f * sx, hy + e * 2.4f * sy), mp);
                float bx = f[0] * sx, by = (f[1] + e * 4.2f) * sy;
                mc.drawOval(new RectF(bx - e * 3.4f * sx, by - e * 3.0f * sy, bx + e * 3.4f * sx, by + e * 3.6f * sy), mp);
            }
        }

        // 2. blurred background
        Bitmap bg = blur(src, 0.004f + 0.03f * blurAmount);
        Canvas c = new Canvas(bg);
        if (effect == P_STAGE || effect == P_STAGE_MONO) {
            c.drawColor(Color.argb(240, 0, 0, 0));
        } else if (effect == P_HIGH_KEY) {
            c.drawColor(Color.argb(240, 255, 255, 255));
        }

        // 3. subject on top, cut out with the mask
        Paint sp = new Paint(Paint.FILTER_BITMAP_FLAG);
        if (effect == P_STAGE_MONO) {
            ColorMatrix g = new ColorMatrix();
            g.setSaturation(0f);
            sp.setColorFilter(new ColorMatrixColorFilter(g));
        } else if (effect == P_HIGH_KEY) {
            ColorMatrix g = new ColorMatrix();
            g.setSaturation(0f);
            g.postConcat(Looks.make(1f, 1.15f, 0.10f, 0f, 0f));
            sp.setColorFilter(new ColorMatrixColorFilter(g));
        } else if (effect == P_STUDIO) {
            sp.setColorFilter(new ColorMatrixColorFilter(Looks.make(1.04f, 1.10f, 0.07f, 0.1f, 0f)));
        }
        int layer = c.saveLayer(0, 0, w, h, null);
        c.drawBitmap(src, 0, 0, sp);
        Paint dstIn = new Paint(Paint.FILTER_BITMAP_FLAG);
        dstIn.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        c.drawBitmap(mask, new Rect(0, 0, mw, mh), new Rect(0, 0, w, h), dstIn);
        c.restoreToCount(layer);
        mask.recycle();
        return bg;
    }

    private static List<float[]> findFaces(Bitmap src) {
        List<float[]> out = new ArrayList<float[]>();
        try {
            int w = src.getWidth(), h = src.getHeight();
            int fw = 480;
            int fh = Math.max(2, (int) (h * (fw / (float) w)));
            Bitmap s = Bitmap.createScaledBitmap(src, fw, fh, true);
            Bitmap s565 = s.copy(Bitmap.Config.RGB_565, false);
            if (s != src) s.recycle();
            FaceDetector fd = new FaceDetector(fw, fh, 5);
            FaceDetector.Face[] faces = new FaceDetector.Face[5];
            int n = fd.findFaces(s565, faces);
            float scale = w / (float) fw;
            for (int i = 0; i < n; i++) {
                FaceDetector.Face f = faces[i];
                if (f == null || f.confidence() < 0.4f) continue;
                PointF mid = new PointF();
                f.getMidPoint(mid);
                out.add(new float[]{mid.x * scale, mid.y * scale, f.eyesDistance() * scale});
            }
            s565.recycle();
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Strong, smooth blur: shrink, box-blur, grow back in steps. Returns a new mutable bitmap. */
    static Bitmap blur(Bitmap src, float radiusFraction) {
        int w = src.getWidth(), h = src.getHeight();
        int d = Math.max(4, (int) (w * radiusFraction / 2f));
        int sw = Math.max(8, w / d), sh = Math.max(8, h / d);
        Bitmap small = Bitmap.createScaledBitmap(src, sw, sh, true);
        Bitmap sm = small.copy(Bitmap.Config.ARGB_8888, true);
        if (small != src) small.recycle();
        int[] px = new int[sw * sh];
        sm.getPixels(px, 0, sw, 0, 0, sw, sh);
        boxBlurRGB(px, sw, sh, 2);
        boxBlurRGB(px, sw, sh, 2);
        sm.setPixels(px, 0, sw, 0, 0, sw, sh);
        Bitmap cur = sm;
        while (cur.getWidth() * 2 < w) {
            Bitmap up = Bitmap.createScaledBitmap(cur, cur.getWidth() * 2, cur.getHeight() * 2, true);
            cur.recycle();
            cur = up;
        }
        Bitmap full = Bitmap.createScaledBitmap(cur, w, h, true);
        if (full != cur) cur.recycle();
        return mutable(full);
    }

    // ------------------------------------------------------------ watermark

    static void watermark(Bitmap b, String text) {
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float size = Math.max(14f, b.getWidth() * 0.026f);
        p.setTextSize(size);
        p.setColor(Color.WHITE);
        p.setShadowLayer(size * 0.15f, 0, 0, Color.argb(200, 0, 0, 0));
        c.drawText(text, size, b.getHeight() - size * 1.2f, p);
    }

    // ------------------------------------------------------------ small helpers

    static int[] histogram(Bitmap small) {
        int[] hist = new int[64];
        int w = small.getWidth(), h = small.getHeight();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int c : px) {
            int l = (int) ((0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255)) / 4f);
            if (l > 63) l = 63;
            hist[l]++;
        }
        return hist;
    }

    private static float skinWeight(float r, float g, float b) {
        float cb = 128f - 0.1687f * r - 0.3313f * g + 0.5f * b;
        float cr = 128f + 0.5f * r - 0.4187f * g - 0.0813f * b;
        float wb = 1f - clampf((Math.abs(cb - 102f) - 20f) / 10f, 0f, 1f);
        float wr = 1f - clampf((Math.abs(cr - 153f) - 15f) / 10f, 0f, 1f);
        float lum = 0.299f * r + 0.587f * g + 0.114f * b;
        if (lum < 40f) return 0f;
        return wb * wr;
    }

    private static int lum(int c) {
        return (int) (0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255));
    }

    private static float bil(float a, float b, float c, float d, float fx, float fy) {
        return (a + (b - a) * fx) * (1f - fy) + (c + (d - c) * fx) * fy;
    }

    private static void axis(int outN, int srcN, int[] i0, int[] i1, float[] f) {
        for (int o = 0; o < outN; o++) {
            float u = (o + 0.5f) * srcN / outN - 0.5f;
            if (u < 0) u = 0;
            if (u > srcN - 1) u = srcN - 1;
            int a = (int) u;
            i0[o] = a;
            i1[o] = Math.min(a + 1, srcN - 1);
            f[o] = u - a;
        }
    }

    private static float clampf(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int cl(float v) {
        return v < 0f ? 0 : (v > 255f ? 255 : (int) v);
    }

    private static void boxBlurF(float[] a, int w, int h, int r) {
        float[] t = new float[a.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                int n = 0;
                for (int k = -r; k <= r; k++) {
                    int xx = x + k;
                    if (xx < 0 || xx >= w) continue;
                    s += a[y * w + xx];
                    n++;
                }
                t[y * w + x] = s / n;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                int n = 0;
                for (int k = -r; k <= r; k++) {
                    int yy = y + k;
                    if (yy < 0 || yy >= h) continue;
                    s += t[yy * w + x];
                    n++;
                }
                a[y * w + x] = s / n;
            }
        }
    }

    private static void boxBlurRGB(int[] px, int w, int h, int r) {
        int[] t = new int[px.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sr = 0, sg = 0, sb = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int xx = x + k;
                    if (xx < 0 || xx >= w) continue;
                    int c = px[y * w + xx];
                    sr += (c >> 16) & 255;
                    sg += (c >> 8) & 255;
                    sb += c & 255;
                    n++;
                }
                t[y * w + x] = 0xff000000 | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sr = 0, sg = 0, sb = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int yy = y + k;
                    if (yy < 0 || yy >= h) continue;
                    int c = t[yy * w + x];
                    sr += (c >> 16) & 255;
                    sg += (c >> 8) & 255;
                    sb += c & 255;
                    n++;
                }
                px[y * w + x] = 0xff000000 | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
            }
        }
    }
}
