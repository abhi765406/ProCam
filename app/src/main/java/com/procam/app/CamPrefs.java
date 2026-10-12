package com.procam.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Saved camera settings. Everything is an int so the settings screen can simply cycle values. */
final class CamPrefs {

    private CamPrefs() {}

    static final String STYLE = "style";          // 0 iPhone, 1 Galaxy, 2 Natural
    static final String LOOK = "look";
    static final String JPEGQ = "jpegq";          // 0=85 1=92 2=97
    static final String MAXMP = "maxmp";          // 0 auto 1 =12MP 2 =8MP 3 =5MP 4 = full
    static final String GRID = "grid";
    static final String LEVEL = "level";
    static final String GEOTAG = "geotag";
    static final String SOUND = "sound";
    static final String MIRROR = "mirror";        // mirror front-camera preview
    static final String MIRROR_SAVE = "mirror_save";
    static final String RAW = "raw";              // also save DNG when supported
    static final String WATERMARK = "watermark";
    static final String PROCESSING = "processing";// 0 full 1 fast 2 off (straight from sensor)
    static final String VIDRES = "vidres";        // 0 best 1 =4K 2 =1080p 3 =720p
    static final String VIDFPS = "vidfps";        // 0 = 30, 1 = 24, 2 = 60
    static final String HEVC = "hevc";
    static final String STAB = "stab";
    static final String CINE = "cine";            // 0 cinematic grade, 1 flat (log-like)
    static final String BEAUTY = "beauty";        // 0..100
    static final String PORTRAIT_BLUR = "pblur";  // 0..100
    static final String PORTRAIT_FX = "pfx";

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("procam", Context.MODE_PRIVATE);
    }

    static int get(Context c, String key, int def) {
        return sp(c).getInt(key, def);
    }

    static void put(Context c, String key, int v) {
        sp(c).edit().putInt(key, v).apply();
    }

    static boolean on(Context c, String key, boolean def) {
        return sp(c).getInt(key, def ? 1 : 0) != 0;
    }
}
