package com.procam.app;

import android.graphics.ColorMatrix;

/**
 * Camera "personalities" (Styles) and colour "Looks".
 *
 * A Style decides how the whole picture is finished: how much the shadows are
 * lifted, how strong the local contrast and sharpening are, how saturated and
 * warm it is. A Look is a creative colour grade on top (Vivid, Dramatic, Mono...).
 */
final class Looks {

    private Looks() {}

    static final class Style {
        final String name;
        final float shadow;     // lift dark areas (0..1)
        final float highlight;  // pull bright areas down (0..1)
        final float clarity;    // local contrast
        final float sharpen;    // fine detail
        final float sat, contrast, bright, warm;
        final float denoise;

        Style(String name, float shadow, float highlight, float clarity, float sharpen,
              float sat, float contrast, float bright, float warm, float denoise) {
            this.name = name;
            this.shadow = shadow;
            this.highlight = highlight;
            this.clarity = clarity;
            this.sharpen = sharpen;
            this.sat = sat;
            this.contrast = contrast;
            this.bright = bright;
            this.warm = warm;
            this.denoise = denoise;
        }

        ColorMatrix matrix() {
            return make(sat, contrast, bright, warm, 0f);
        }
    }

    static final class Look {
        final String name;
        final float sat, contrast, bright, warm, fade, vignette, grain;

        Look(String name, float sat, float contrast, float bright, float warm, float fade,
             float vignette, float grain) {
            this.name = name;
            this.sat = sat;
            this.contrast = contrast;
            this.bright = bright;
            this.warm = warm;
            this.fade = fade;
            this.vignette = vignette;
            this.grain = grain;
        }

        ColorMatrix matrix() {
            return make(sat, contrast, bright, warm, fade);
        }
    }

    static final Style[] STYLES = {
            // Natural skin, balanced shadows, gentle punch, slightly warm.
            new Style("iPhone Style", 0.40f, 0.30f, 0.30f, 0.35f, 1.06f, 1.08f, 0.00f, 0.04f, 0.25f),
            // Brighter, punchier, more saturated, stronger sharpening.
            new Style("Galaxy Style", 0.50f, 0.22f, 0.38f, 0.55f, 1.20f, 1.10f, 0.03f, 0.00f, 0.30f),
            // Neutral, close to what the sensor saw.
            new Style("Natural", 0.12f, 0.08f, 0.08f, 0.15f, 1.00f, 1.00f, 0.00f, 0.00f, 0.10f),
    };

    static final Look[] IPHONE_LOOKS = {
            new Look("Standard", 1.00f, 1.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f),
            new Look("Vivid", 1.28f, 1.08f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f),
            new Look("Vivid Warm", 1.28f, 1.08f, 0.02f, 0.55f, 0.00f, 0.00f, 0.00f),
            new Look("Vivid Cool", 1.28f, 1.08f, 0.00f, -0.55f, 0.00f, 0.00f, 0.00f),
            new Look("Dramatic", 0.95f, 1.30f, -0.04f, 0.00f, 0.00f, 0.15f, 0.00f),
            new Look("Dramatic Warm", 0.95f, 1.30f, -0.03f, 0.50f, 0.00f, 0.15f, 0.00f),
            new Look("Dramatic Cool", 0.95f, 1.30f, -0.04f, -0.50f, 0.00f, 0.15f, 0.00f),
            new Look("Mono", 0.00f, 1.06f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f),
            new Look("Silvertone", 0.00f, 0.95f, 0.03f, 0.00f, 0.25f, 0.00f, 0.00f),
            new Look("Noir", 0.00f, 1.50f, -0.06f, 0.00f, 0.00f, 0.28f, 0.02f),
            new Look("Document", 0.00f, 1.90f, 0.16f, 0.00f, 0.00f, 0.00f, 0.00f),
    };

    static final Look[] GALAXY_LOOKS = {
            new Look("Original", 1.00f, 1.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f),
            new Look("Vivid", 1.40f, 1.10f, 0.02f, 0.00f, 0.00f, 0.00f, 0.00f),
            new Look("Soft", 0.90f, 0.92f, 0.04f, 0.10f, 0.15f, 0.00f, 0.00f),
            new Look("Warm", 1.08f, 1.04f, 0.01f, 0.60f, 0.00f, 0.00f, 0.00f),
            new Look("Cool", 1.05f, 1.04f, 0.00f, -0.60f, 0.00f, 0.00f, 0.00f),
            new Look("Cinematic", 0.85f, 1.22f, -0.02f, -0.20f, 0.20f, 0.22f, 0.02f),
            new Look("Fade", 0.80f, 0.90f, 0.05f, 0.05f, 0.35f, 0.00f, 0.00f),
            new Look("Retro", 0.80f, 1.10f, 0.00f, 0.60f, 0.30f, 0.30f, 0.04f),
            new Look("B&W", 0.00f, 1.10f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f),
            new Look("Noir", 0.00f, 1.50f, -0.06f, 0.00f, 0.00f, 0.28f, 0.02f),
            new Look("Document", 0.00f, 1.90f, 0.16f, 0.00f, 0.00f, 0.00f, 0.00f),
    };

    static Look[] looksFor(int styleIndex) {
        return styleIndex == 1 ? GALAXY_LOOKS : IPHONE_LOOKS;
    }

    /** Builds a colour matrix: saturation, contrast (about mid grey), brightness, warmth, fade. */
    static ColorMatrix make(float sat, float contrast, float bright, float warm, float fade) {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(sat);
        float c = contrast * (1f - 0.14f * fade);
        float t = (-0.5f * c + 0.5f + bright) * 255f + 34f * fade;
        m.postConcat(new ColorMatrix(new float[]{
                c, 0, 0, 0, t,
                0, c, 0, 0, t,
                0, 0, c, 0, t,
                0, 0, 0, 1, 0}));
        if (warm != 0f) {
            m.postConcat(new ColorMatrix(new float[]{
                    1f + 0.10f * warm, 0, 0, 0, 0,
                    0, 1f + 0.015f * warm, 0, 0, 0,
                    0, 0, 1f - 0.12f * warm, 0, 0,
                    0, 0, 0, 1, 0}));
        }
        return m;
    }

    /** Style matrix followed by look matrix, as 20 floats (what Engine and the live preview use). */
    static float[] compose(int styleIndex, int lookIndex) {
        Style s = STYLES[styleIndex];
        Look[] list = looksFor(styleIndex);
        Look l = list[Math.max(0, Math.min(lookIndex, list.length - 1))];
        ColorMatrix cm = new ColorMatrix();
        cm.postConcat(s.matrix());
        cm.postConcat(l.matrix());
        return cm.getArray();
    }

    static Look look(int styleIndex, int lookIndex) {
        Look[] list = looksFor(styleIndex);
        return list[Math.max(0, Math.min(lookIndex, list.length - 1))];
    }
}
