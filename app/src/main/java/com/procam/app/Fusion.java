package com.procam.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.ArrayList;
import java.util.List;

/** Multi-frame photography: HDR exposure fusion and Night mode (align + average). */
final class Fusion {

    private Fusion() {}

    // ------------------------------------------------------------------ HDR

    /**
     * Blends bracketed frames (dark, normal, bright). For every area of the picture
     * the best-exposed frame gets the highest weight, so bright skies keep detail
     * and shadows are opened up. Weights are smooth so there are no visible seams.
     */
    static Bitmap hdr(List<byte[]> jpegs, int maxPixels) {
        int n = jpegs.size();
        List<Bitmap> frames = new ArrayList<Bitmap>();
        for (byte[] j : jpegs) {
            Bitmap b = Engine.decode(j, maxPixels);
            if (b != null) frames.add(b);
        }
        if (frames.isEmpty()) return null;
        if (frames.size() == 1) return frames.get(0);
        n = frames.size();
        int w = frames.get(0).getWidth(), h = frames.get(0).getHeight();
        for (int i = n - 1; i >= 1; i--) {
            if (frames.get(i).getWidth() != w || frames.get(i).getHeight() != h) {
                frames.get(i).recycle();
                frames.remove(i);
            }
        }
        n = frames.size();

        final int sw = Math.max(8, w / 16), sh = Math.max(8, h / 16);
        float[][] wm = new float[n][sw * sh];
        for (int i = 0; i < n; i++) {
            Bitmap s = Bitmap.createScaledBitmap(frames.get(i), sw, sh, true);
            int[] sp = new int[sw * sh];
            s.getPixels(sp, 0, sw, 0, 0, sw, sh);
            if (s != frames.get(i)) s.recycle();
            for (int k = 0; k < sp.length; k++) {
                int c = sp[k];
                float l = (0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255)) / 255f;
                float d = l - 0.5f;
                wm[i][k] = (float) Math.exp(-(d * d) / (2f * 0.22f * 0.22f)) + 0.001f;
            }
        }
        for (int k = 0; k < sw * sh; k++) {
            float sum = 0;
            for (int i = 0; i < n; i++) sum += wm[i][k];
            for (int i = 0; i < n; i++) wm[i][k] /= sum;
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] x0 = new int[w], x1 = new int[w];
        float[] fx = new float[w];
        for (int x = 0; x < w; x++) {
            float u = (x + 0.5f) * sw / w - 0.5f;
            if (u < 0) u = 0;
            if (u > sw - 1) u = sw - 1;
            x0[x] = (int) u;
            x1[x] = Math.min(x0[x] + 1, sw - 1);
            fx[x] = u - x0[x];
        }
        final int STRIPE = 64;
        int[][] px = new int[n][w * STRIPE];
        int[] res = new int[w * STRIPE];
        for (int y0 = 0; y0 < h; y0 += STRIPE) {
            int rows = Math.min(STRIPE, h - y0);
            for (int i = 0; i < n; i++) frames.get(i).getPixels(px[i], 0, w, 0, y0, w, rows);
            for (int ry = 0; ry < rows; ry++) {
                float v = (y0 + ry + 0.5f) * sh / h - 0.5f;
                if (v < 0) v = 0;
                if (v > sh - 1) v = sh - 1;
                int ya = (int) v, yb = Math.min(ya + 1, sh - 1);
                float fy = v - ya;
                for (int x = 0; x < w; x++) {
                    float r = 0, g = 0, b = 0, ws = 0;
                    for (int i = 0; i < n; i++) {
                        float[] m = wm[i];
                        float a = m[ya * sw + x0[x]], bb = m[ya * sw + x1[x]];
                        float c2 = m[yb * sw + x0[x]], d2 = m[yb * sw + x1[x]];
                        float wt = (a + (bb - a) * fx[x]) * (1f - fy) + (c2 + (d2 - c2) * fx[x]) * fy;
                        int c = px[i][ry * w + x];
                        r += wt * ((c >> 16) & 255);
                        g += wt * ((c >> 8) & 255);
                        b += wt * (c & 255);
                        ws += wt;
                    }
                    if (ws <= 0f) ws = 1f;
                    int ir = (int) (r / ws), ig = (int) (g / ws), ib = (int) (b / ws);
                    res[ry * w + x] = 0xff000000 | (ir << 16) | (ig << 8) | ib;
                }
            }
            out.setPixels(res, 0, w, 0, y0, w, rows);
        }
        for (Bitmap f : frames) f.recycle();
        return out;
    }

    // ------------------------------------------------------------------ Night

    /**
     * Aligns every frame to the first one (hand shake) and averages them.
     * Averaging N frames cuts random noise by about sqrt(N).
     */
    static Bitmap night(List<byte[]> jpegs, int maxPixels) {
        Bitmap acc = null;
        Bitmap refSmall = null;
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        int count = 0;
        for (int i = 0; i < jpegs.size(); i++) {
            Bitmap f = Engine.decode(jpegs.get(i), maxPixels);
            if (f == null) continue;
            if (acc == null) {
                acc = f;
                refSmall = grayCenter(acc);
                count = 1;
                continue;
            }
            if (f.getWidth() != acc.getWidth() || f.getHeight() != acc.getHeight()) {
                f.recycle();
                continue;
            }
            int[] sh = shift(refSmall, f);
            count++;
            paint.setAlpha(Math.max(1, 255 / count));
            Canvas c = new Canvas(acc);
            c.drawBitmap(f, sh[0], sh[1], paint);
            f.recycle();
        }
        if (refSmall != null) refSmall.recycle();
        return acc;
    }

    /** Central quarter of the picture, used to measure movement between frames. */
    private static Bitmap grayCenter(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int cw = w / 2, ch = h / 2;
        return Bitmap.createBitmap(b, w / 4, h / 4, cw, ch);
    }

    private static int[] gray(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            px[i] = (((c >> 16) & 255) * 77 + ((c >> 8) & 255) * 150 + (c & 255) * 29) >> 8;
        }
        return px;
    }

    /** Returns {dx, dy} in pixels to draw `cur` so it lines up with the reference. */
    static int[] shift(Bitmap refCenter, Bitmap cur) {
        try {
            int cw = refCenter.getWidth(), ch = refCenter.getHeight();
            Bitmap curCenter = grayCenter(cur);
            // coarse: 1/8 scale, +-8
            int sw = Math.max(16, cw / 8), sh = Math.max(16, ch / 8);
            Bitmap ra = Bitmap.createScaledBitmap(refCenter, sw, sh, true);
            Bitmap ca = Bitmap.createScaledBitmap(curCenter, sw, sh, true);
            int[] a = gray(ra), b = gray(ca);
            if (ra != refCenter) ra.recycle();
            if (ca != curCenter) ca.recycle();
            int[] best = search(a, b, sw, sh, 0, 0, 8, 1);
            int dx = best[0] * (cw / sw), dy = best[1] * (ch / sh);
            // fine: 1/2 scale around the coarse answer
            int fw = cw / 2, fh = ch / 2;
            Bitmap rb = Bitmap.createScaledBitmap(refCenter, fw, fh, true);
            Bitmap cb = Bitmap.createScaledBitmap(curCenter, fw, fh, true);
            int[] a2 = gray(rb), b2 = gray(cb);
            if (rb != refCenter) rb.recycle();
            if (cb != curCenter) cb.recycle();
            // search() works with match offsets, which are the negative of the draw shift
            int[] fine = search(a2, b2, fw, fh, -dx / 2, -dy / 2, 6, 3);
            curCenter.recycle();
            return new int[]{fine[0] * 2, fine[1] * 2};
        } catch (Throwable t) {
            return new int[]{0, 0};
        }
    }

    /**
     * Finds the offset (around cx,cy within +-range) where b best matches a.
     * Result {sx, sy} means: draw b shifted by (-sx, -sy)... expressed as the shift to apply to b.
     */
    private static int[] search(int[] a, int[] b, int w, int h, int cx, int cy, int range, int stride) {
        int margin = range + Math.abs(Math.max(Math.abs(cx), Math.abs(cy))) + 2;
        long bestSad = Long.MAX_VALUE;
        int bx = cx, by = cy;
        for (int dy = cy - range; dy <= cy + range; dy++) {
            for (int dx = cx - range; dx <= cx + range; dx++) {
                long sad = 0;
                for (int y = margin; y < h - margin; y += stride) {
                    int ya = y * w;
                    int yb = (y + dy) * w;
                    for (int x = margin; x < w - margin; x += stride) {
                        int d = a[ya + x] - b[yb + x + dx];
                        sad += d < 0 ? -d : d;
                    }
                }
                if (sad < bestSad) {
                    bestSad = sad;
                    bx = dx;
                    by = dy;
                }
            }
        }
        // b content at (x+dx, y+dy) matches a at (x, y), so draw b moved by (-dx, -dy)
        return new int[]{-bx, -by};
    }
}
