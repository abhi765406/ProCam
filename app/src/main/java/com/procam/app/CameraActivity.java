package com.procam.app;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CaptureResult;
import android.location.Location;
import android.location.LocationManager;
import android.media.MediaActionSound;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The whole camera screen. */
public class CameraActivity extends Activity implements CamController.Listener {

    // modes
    private static final int PHOTO = 0, PORTRAIT = 1, NIGHT = 2, PRO = 3, VIDEO = 4, CINEMA = 5, SLOMO = 6, LAPSE = 7;
    private static final String[] MODE_NAMES = {"PHOTO", "PORTRAIT", "NIGHT", "PRO", "VIDEO", "CINEMATIC", "SLOW-MO", "TIME-LAPSE"};
    private static final int REQ_PERMS = 9;
    private static final String[] FLASH_NAMES = {"FLASH OFF", "FLASH AUTO", "FLASH ON", "TORCH"};
    private static final String[] WB_NAMES = {"AUTO", "2700K Bulb", "4000K Fluor.", "5500K Day", "6500K Cloudy", "7500K Shade"};
    private static final int[] WB_MODES = {1, 2, 3, 5, 6, 8};
    private static final String[] PORTRAIT_FX = {"Natural", "Studio", "Stage", "Stage Mono", "High-key Mono"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private CamController cam;
    private TextureView tv;
    private FrameLayout root, stage;
    private Overlays.Grid grid;
    private Overlays.Level level;
    private Overlays.Histogram histogram;
    private Overlays.FocusRing focusRing;
    private Overlays.Guide guide;
    private Overlays.Shutter shutter;
    private LinearLayout topBar, bottom, zoomRow, modeRow, proPanel, looksRow;
    private HorizontalScrollView looksScroll;
    private ScrollView settingsView;
    private TextView infoText, timerText, flashChip, timerChip, hdrChip, burstChip, ratioChip, lookChip, proChip, pauseChip;
    private ImageView thumb;
    private final List<TextView> modeChips = new ArrayList<TextView>();

    // pro panel widgets
    private SeekBar evBar, isoBar, shutBar, focusBar, wbBar;
    private TextView evVal, isoVal, shutVal, focusVal, wbVal, rawChip, isoAuto, focusAuto;
    private LinearLayout isoRow, shutRow, focusRow;

    // state
    private int mode = PHOTO;
    private boolean hdrOn, burstOn, busy, recordingUi, proVisible, looksVisible, paused;
    private int timerSec = 0;
    private int lapseSec = 2;
    private int deviceDeg = 0;
    private String currentId;
    private boolean front;
    private long recStart;
    private Uri lastUri;
    private boolean lastIsVideo;
    private float evAccum = 0f;
    private volatile int liveIso;
    private volatile long liveExp;
    private MediaActionSound sound;
    private OrientationEventListener orient;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestures;
    private boolean opened;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        cam = new CamController(this, this);
        sound = new MediaActionSound();
        buildUi();
        applySettings(false);
        orient = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                deviceDeg = ((o + 45) / 90 * 90) % 360;
            }
        };
        if (!hasPerms()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERMS);
        }
    }

    private boolean hasPerms() {
        return Build.VERSION.SDK_INT < 23
                || (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
    }

    private boolean hasMic() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (hasPerms()) openCam();
        else toast("Camera and storage permission are needed to take and save pictures.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        paused = false;
        if (orient.canDetectOrientation()) orient.enable();
        level.start();
        ui.post(tick);
        openCam();
    }

    @Override
    protected void onPause() {
        super.onPause();
        paused = true;
        ui.removeCallbacksAndMessages(null);
        orient.disable();
        level.stop();
        recordingUi = false;
        shutter.set(mode >= VIDEO, false);
        cam.close();
        opened = false;
        busy = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdown();
        sound.release();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (e.getRepeatCount() == 0) onShutter();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    @Override
    public void onBackPressed() {
        if (settingsView.getVisibility() == View.VISIBLE) {
            settingsView.setVisibility(View.GONE);
            return;
        }
        super.onBackPressed();
    }

    private void openCam() {
        if (!hasPerms() || paused || opened || !tv.isAvailable()) return;
        if (cam.cameras.isEmpty()) {
            toast("No camera found on this phone");
            return;
        }
        if (currentId == null) currentId = cam.defaultBackId();
        opened = true;
        cam.open(currentId, tv);
    }

    // ------------------------------------------------------------------ CamController.Listener

    @Override
    public void onSession(final boolean video, final int pw, final int ph) {
        runOnUiThread(new Runnable() {
            public void run() {
                front = cam.front;
                layoutStage(pw, ph);
                tv.setScaleX(front && CamPrefs.on(CameraActivity.this, CamPrefs.MIRROR, true) ? -1f : 1f);
                updatePreviewLook();
                rebuildZoomChips();
                refreshChips();
                setupProPanel();
                hint(null);
            }
        });
    }

    @Override
    public void onError(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                opened = false;
                toast(msg);
            }
        });
    }

    @Override
    public void onResult(CaptureResult r) {
        Integer iso = r.get(CaptureResult.SENSOR_SENSITIVITY);
        Long exp = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        if (iso != null) liveIso = iso;
        if (exp != null) liveExp = exp;
    }

    @Override
    public void onVideoDone(final File f, final int slowFactor, final String error) {
        runOnUiThread(new Runnable() {
            public void run() {
                recordingUi = false;
                busy = false;
                shutter.set(true, false);
                pauseChip.setVisibility(View.GONE);
                hint(null);
                if (error != null || f == null) {
                    toast(error == null ? "Recording failed" : error);
                    return;
                }
                finishVideo(f, slowFactor);
            }
        });
    }

    // ------------------------------------------------------------------ UI construction

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView chip(String text, final Runnable click) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(11.5f);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(110, 0, 0, 0));
        g.setCornerRadius(dp(14));
        t.setBackgroundDrawable(g);
        if (click != null) {
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    click.run();
                }
            });
        }
        return t;
    }

    private void setChipActive(TextView t, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(on ? Color.rgb(255, 204, 0) : Color.argb(110, 0, 0, 0));
        g.setCornerRadius(dp(14));
        t.setBackgroundDrawable(g);
        t.setTextColor(on ? Color.BLACK : Color.WHITE);
    }

    private LinearLayout.LayoutParams lpc(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        // ---- viewfinder stage
        stage = new FrameLayout(this);
        tv = new TextureView(this);
        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                openCam();
            }

            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            }

            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                return true;
            }

            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
        stage.addView(tv, new FrameLayout.LayoutParams(-1, -1));
        guide = new Overlays.Guide(this);
        grid = new Overlays.Grid(this);
        level = new Overlays.Level(this);
        focusRing = new Overlays.FocusRing(this);
        histogram = new Overlays.Histogram(this);
        stage.addView(guide, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(level, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(focusRing, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(dp(96), dp(46));
        hp.gravity = Gravity.TOP | Gravity.LEFT;
        hp.setMargins(dp(8), dp(8), 0, 0);
        stage.addView(histogram, hp);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        setupGestures();

        // ---- top bar
        HorizontalScrollView topScroll = new HorizontalScrollView(this);
        topScroll.setHorizontalScrollBarEnabled(false);
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setPadding(dp(8), dp(10), dp(8), dp(6));
        topScroll.addView(topBar);
        flashChip = chip("FLASH OFF", new Runnable() {
            public void run() {
                int n = (cam.s.flash + 1) % 4;
                cam.s.flash = n;
                refreshChips();
                cam.updatePreview();
            }
        });
        timerChip = chip("TIMER OFF", new Runnable() {
            public void run() {
                if (mode == LAPSE) {
                    lapseSec = lapseSec == 2 ? 5 : (lapseSec == 5 ? 10 : (lapseSec == 10 ? 30 : 2));
                } else {
                    timerSec = timerSec == 0 ? 3 : (timerSec == 3 ? 10 : 0);
                }
                refreshChips();
            }
        });
        hdrChip = chip("HDR OFF", new Runnable() {
            public void run() {
                hdrOn = !hdrOn;
                if (hdrOn) burstOn = false;
                refreshChips();
                hint(hdrOn ? "HDR: 3 shots are blended to keep sky and shadows" : null);
            }
        });
        burstChip = chip("BURST OFF", new Runnable() {
            public void run() {
                burstOn = !burstOn;
                if (burstOn) hdrOn = false;
                refreshChips();
                hint(burstOn ? "Burst: 8 quick shots, saved as they are" : null);
            }
        });
        ratioChip = chip("4:3", new Runnable() {
            public void run() {
                cam.s.aspect = (cam.s.aspect + 1) % 3;
                refreshChips();
                cam.createPreviewSession();
            }
        });
        lookChip = chip("LOOK", new Runnable() {
            public void run() {
                looksVisible = !looksVisible;
                if (looksVisible) {
                    proVisible = mode == PRO;
                    buildLooksRow();
                }
                refreshPanels();
            }
        });
        proChip = chip("MANUAL", new Runnable() {
            public void run() {
                proVisible = !proVisible;
                if (proVisible) looksVisible = false;
                refreshPanels();
            }
        });
        TextView settingsChip = chip("SETTINGS", new Runnable() {
            public void run() {
                settingsView.setVisibility(View.VISIBLE);
            }
        });
        TextView[] bar = {flashChip, timerChip, hdrChip, burstChip, ratioChip, lookChip, proChip, settingsChip};
        for (TextView c : bar) topBar.addView(c, lpc(-2, -2, 3, 0, 3, 0));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2);
        tp.gravity = Gravity.TOP;
        root.addView(topScroll, tp);

        // ---- hint / timers
        infoText = new TextView(this);
        infoText.setTextColor(Color.WHITE);
        infoText.setTextSize(13);
        infoText.setGravity(Gravity.CENTER);
        infoText.setPadding(dp(12), dp(4), dp(12), dp(4));
        infoText.setShadowLayer(4, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-1, -2);
        ip.gravity = Gravity.TOP;
        ip.topMargin = dp(52);
        root.addView(infoText, ip);

        timerText = new TextView(this);
        timerText.setTextColor(Color.WHITE);
        timerText.setTextSize(90);
        timerText.setGravity(Gravity.CENTER);
        timerText.setShadowLayer(10, 0, 0, Color.BLACK);
        timerText.setVisibility(View.GONE);
        root.addView(timerText, new FrameLayout.LayoutParams(-1, -1));

        // ---- bottom controls
        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(Color.argb(150, 0, 0, 0));
        bottom.setPadding(0, dp(6), 0, dp(14));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2);
        bp.gravity = Gravity.BOTTOM;
        root.addView(bottom, bp);

        buildProPanel();
        bottom.addView(proPanel);

        looksScroll = new HorizontalScrollView(this);
        looksScroll.setHorizontalScrollBarEnabled(false);
        looksRow = new LinearLayout(this);
        looksRow.setOrientation(LinearLayout.HORIZONTAL);
        looksRow.setPadding(dp(8), dp(4), dp(8), dp(4));
        looksScroll.addView(looksRow);
        looksScroll.setVisibility(View.GONE);
        bottom.addView(looksScroll);

        zoomRow = new LinearLayout(this);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        zoomRow.setGravity(Gravity.CENTER);
        bottom.addView(zoomRow, lpc(-1, -2, 0, 2, 0, 4));

        HorizontalScrollView modeScroll = new HorizontalScrollView(this);
        modeScroll.setHorizontalScrollBarEnabled(false);
        modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(dp(120), 0, dp(120), 0);
        modeScroll.addView(modeRow);
        for (int i = 0; i < MODE_NAMES.length; i++) {
            final int m = i;
            TextView t = new TextView(this);
            t.setText(MODE_NAMES[i]);
            t.setTextSize(13);
            t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            t.setPadding(dp(12), dp(8), dp(12), dp(8));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    setMode(m);
                }
            });
            modeChips.add(t);
            modeRow.addView(t);
        }
        bottom.addView(modeScroll, lpc(-1, -2, 0, 0, 0, 6));

        LinearLayout shutterRow = new LinearLayout(this);
        shutterRow.setOrientation(LinearLayout.HORIZONTAL);
        shutterRow.setGravity(Gravity.CENTER_VERTICAL);
        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable tg = new GradientDrawable();
        tg.setColor(Color.argb(120, 255, 255, 255));
        tg.setCornerRadius(dp(10));
        thumb.setBackgroundDrawable(tg);
        thumb.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                openLast();
            }
        });
        shutter = new Overlays.Shutter(this);
        shutter.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                onShutter();
            }
        });
        TextView flip = chip("FLIP", new Runnable() {
            public void run() {
                flipCamera();
            }
        });
        pauseChip = chip("PAUSE", new Runnable() {
            public void run() {
                boolean p = "PAUSE".contentEquals(pauseChip.getText());
                cam.pauseVideo(p);
                pauseChip.setText(p ? "RESUME" : "PAUSE");
            }
        });
        pauseChip.setVisibility(View.GONE);
        shutterRow.addView(wrapCenter(thumb, 54, 54), new LinearLayout.LayoutParams(0, dp(78), 1f));
        shutterRow.addView(wrapCenter(shutter, 74, 74), new LinearLayout.LayoutParams(0, dp(78), 1f));
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER);
        right.addView(flip);
        right.addView(pauseChip, lpc(-2, -2, 0, 6, 0, 0));
        shutterRow.addView(right, new LinearLayout.LayoutParams(0, dp(78), 1f));
        bottom.addView(shutterRow);

        buildSettings();
        setMode(PHOTO);
    }

    private View wrapCenter(View v, int wdp, int hdp) {
        FrameLayout f = new FrameLayout(this);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(wdp), dp(hdp));
        p.gravity = Gravity.CENTER;
        f.addView(v, p);
        return f;
    }

    // ------------------------------------------------------------------ gestures

    private void setupGestures() {
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float z = cam.s.zoom * d.getScaleFactor();
                setZoom(z);
                return true;
            }
        });
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                evAccum = 0f;
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (recordingUi && mode == LAPSE) return true;
                focus(e.getX(), e.getY(), false);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                focus(e.getX(), e.getY(), true);
                hint("AE/AF LOCK - tap anywhere to release");
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scaleDetector.isInProgress() || cam.s.manualExp || cam.evRange == null) return false;
                if (Math.abs(dy) < Math.abs(dx)) return false;
                evAccum += dy / dp(24);
                int steps = (int) evAccum;
                if (steps != 0) {
                    evAccum -= steps;
                    int idx = cam.s.evIndex + steps;
                    idx = Math.max(cam.evRange.getLower(), Math.min(cam.evRange.getUpper(), idx));
                    cam.s.evIndex = idx;
                    cam.updatePreview();
                    hint(String.format(Locale.US, "EV %+.1f", idx * cam.evStep));
                    if (evBar != null) evBar.setProgress(idx - cam.evRange.getLower());
                }
                return true;
            }
        });
        stage.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                scaleDetector.onTouchEvent(e);
                if (!scaleDetector.isInProgress()) gestures.onTouchEvent(e);
                return true;
            }
        });
    }

    private void focus(float x, float y, boolean lock) {
        if (stage.getWidth() == 0) return;
        boolean wasLocked = cam.s.afLock;
        if (!lock && wasLocked) {
            cam.clearFocusLock();
            hint(null);
        }
        float u = x / stage.getWidth(), v = y / stage.getHeight();
        boolean mirrored = front && tv.getScaleX() < 0;
        cam.focusAt(u, v, lock, mirrored);
        focusRing.show(x, y, lock);
        if (CamPrefs.on(this, CamPrefs.SOUND, true)) sound.play(MediaActionSound.FOCUS_COMPLETE);
    }

    private void setZoom(float z) {
        z = Math.max(cam.minZoom, Math.min(cam.maxZoom, z));
        cam.s.zoom = z;
        cam.updatePreview();
        hint(String.format(Locale.US, "%.1fx", z));
        refreshZoomChips();
    }

    // ------------------------------------------------------------------ layout of the viewfinder

    private void layoutStage(int pw, int ph) {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        // buffer is landscape (pw > ph); on a portrait screen the picture is ph wide and pw tall
        int h = (int) ((long) sw * pw / Math.max(1, ph));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(sw, h);
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        float ratio = h / (float) sw;
        int top = ratio > 1.5f ? 0 : dp(46);
        if (ratio < 1.4f) top = dp(52);
        p.topMargin = top;
        stage.setLayoutParams(p);
        guide.mode = mode == CINEMA ? 2 : (cam.s.aspect == 2 && mode < VIDEO ? 1 : 0);
        guide.invalidate();
    }

    private void hint(String text) {
        infoText.setText(text == null ? defaultHint() : text);
        ui.removeCallbacks(clearHint);
        if (text != null) ui.postDelayed(clearHint, 2500);
    }

    private final Runnable clearHint = new Runnable() {
        public void run() {
            infoText.setText(defaultHint());
        }
    };

    private String defaultHint() {
        if (recordingUi) return "";
        switch (mode) {
            case PORTRAIT: return "Portrait: stand 1-2 m from your subject";
            case NIGHT: return "Night: hold still when you press the shutter";
            case SLOMO: return "Slow-mo: needs good light";
            case LAPSE: return "Time-lapse: one frame every " + lapseSec + " s - use a tripod";
            case CINEMA: return "Cinematic: 24 fps film look";
            default: return "";
        }
    }

    // ------------------------------------------------------------------ periodic UI work

    private int tickCount = 0;
    private final Runnable tick = new Runnable() {
        public void run() {
            if (paused) return;
            tickCount++;
            try {
                if (proVisible && !cam.s.manualExp) {
                    isoVal.setText("ISO " + liveIso);
                    shutVal.setText(fmtShutter(liveExp) + " A");
                }
                if (recordingUi) {
                    long sec = (System.currentTimeMillis() - recStart) / 1000;
                    infoText.setText(String.format(Locale.US, "REC  %02d:%02d", sec / 60, sec % 60));
                    infoText.setTextColor(sec % 2 == 0 ? Color.rgb(255, 69, 58) : Color.WHITE);
                } else {
                    infoText.setTextColor(Color.WHITE);
                }
                if (tickCount % 2 == 0 && opened && !recordingUi && tv.isAvailable()) {
                    Bitmap bm = tv.getBitmap(64, 48);
                    if (bm != null) {
                        int[] h = Engine.histogram(bm);
                        bm.recycle();
                        histogram.set(h);
                        double sum = 0, tot = 0;
                        for (int i = 0; i < h.length; i++) {
                            sum += (double) i * h[i];
                            tot += h[i];
                        }
                        double avg = tot > 0 ? sum / (tot * 63.0) : 1;
                        if (avg < 0.16 && mode == PHOTO && infoText.getText().length() == 0) {
                            infoText.setText("Dark scene - try NIGHT mode");
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            ui.postDelayed(this, 450);
        }
    };

    // ------------------------------------------------------------------ modes and chips

    private void setMode(int m) {
        mode = m;
        boolean video = m >= VIDEO;
        boolean wasVideo = cam.s.video;
        cam.s.video = video;
        cam.s.tone = m == CINEMA ? (CamPrefs.get(this, CamPrefs.CINE, 0) == 0 ? CamController.T_CINE : CamController.T_FLAT) : CamController.T_NONE;
        applyFps();
        cam.s.stab = m == CINEMA || CamPrefs.get(this, CamPrefs.STAB, 1) == 1;
        if (m != PHOTO) {
            hdrOn = false;
            burstOn = false;
        }
        if (m == NIGHT || m == PORTRAIT) cam.s.manualExp = false;
        proVisible = m == PRO;
        looksVisible = false;
        for (int i = 0; i < modeChips.size(); i++) {
            TextView t = modeChips.get(i);
            t.setTextColor(i == m ? Color.rgb(255, 204, 0) : Color.WHITE);
        }
        shutter.set(video, false);
        guide.mode = m == CINEMA ? 2 : (cam.s.aspect == 2 && !video ? 1 : 0);
        guide.invalidate();
        if (opened && wasVideo != video) cam.createPreviewSession();
        else if (opened) cam.updatePreview();
        refreshChips();
        refreshPanels();
        updatePreviewLook();
        hint(null);
    }

    private void applyFps() {
        int[] fpsTable = {30, 24, 60};
        int fps = fpsTable[Math.max(0, Math.min(2, CamPrefs.get(this, CamPrefs.VIDFPS, 0)))];
        if (mode == CINEMA) fps = 24;
        try {
            if (opened && !cam.supportsFps(fps)) fps = 30;
        } catch (Throwable ignored) {
        }
        cam.s.fps = fps;
    }

    private void refreshChips() {
        boolean video = mode >= VIDEO;
        flashChip.setVisibility(cam.hasFlash ? View.VISIBLE : View.GONE);
        flashChip.setText(FLASH_NAMES[cam.s.flash]);
        setChipActive(flashChip, cam.s.flash != 0);
        boolean timerOk = !video || mode == LAPSE;
        timerChip.setVisibility(timerOk ? View.VISIBLE : View.GONE);
        if (mode == LAPSE) timerChip.setText("EVERY " + lapseSec + "s");
        else timerChip.setText(timerSec == 0 ? "TIMER OFF" : "TIMER " + timerSec + "s");
        setChipActive(timerChip, timerSec > 0 && mode != LAPSE);
        hdrChip.setVisibility(mode == PHOTO ? View.VISIBLE : View.GONE);
        hdrChip.setText(hdrOn ? "HDR ON" : "HDR OFF");
        setChipActive(hdrChip, hdrOn);
        burstChip.setVisibility(mode == PHOTO ? View.VISIBLE : View.GONE);
        burstChip.setText(burstOn ? "BURST ON" : "BURST OFF");
        setChipActive(burstChip, burstOn);
        ratioChip.setVisibility(video ? View.GONE : View.VISIBLE);
        ratioChip.setText(cam.s.aspect == 0 ? "4:3" : (cam.s.aspect == 1 ? "16:9" : "1:1"));
        lookChip.setVisibility(video ? View.GONE : View.VISIBLE);
        lookChip.setText(mode == PORTRAIT ? "EFFECT" : "LOOK");
        setChipActive(lookChip, looksVisible);
        setChipActive(proChip, proVisible);
    }

    private void refreshPanels() {
        proPanel.setVisibility(proVisible ? View.VISIBLE : View.GONE);
        looksScroll.setVisibility(looksVisible && mode < VIDEO ? View.VISIBLE : View.GONE);
        refreshChips();
    }

    private void updatePreviewLook() {
        if (mode < VIDEO) {
            int style = CamPrefs.get(this, CamPrefs.STYLE, 0);
            int look = CamPrefs.get(this, CamPrefs.LOOK, 0);
            Paint p = new Paint();
            p.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(Looks.compose(style, look))));
            tv.setLayerType(View.LAYER_TYPE_HARDWARE, p);
        } else {
            tv.setLayerType(View.LAYER_TYPE_NONE, null);
        }
    }

    private void buildLooksRow() {
        looksRow.removeAllViews();
        final boolean portrait = mode == PORTRAIT;
        final String[] names;
        if (portrait) {
            names = PORTRAIT_FX;
        } else {
            Looks.Look[] list = Looks.looksFor(CamPrefs.get(this, CamPrefs.STYLE, 0));
            names = new String[list.length];
            for (int i = 0; i < list.length; i++) names[i] = list[i].name;
        }
        final String key = portrait ? CamPrefs.PORTRAIT_FX : CamPrefs.LOOK;
        final List<TextView> chips = new ArrayList<TextView>();
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView c = chip(names[i], new Runnable() {
                public void run() {
                    CamPrefs.put(CameraActivity.this, key, idx);
                    for (int k = 0; k < chips.size(); k++) setChipActive(chips.get(k), k == idx);
                    updatePreviewLook();
                }
            });
            chips.add(c);
            looksRow.addView(c, lpc(-2, -2, 3, 0, 3, 0));
        }
        int sel = CamPrefs.get(this, key, 0);
        for (int k = 0; k < chips.size(); k++) setChipActive(chips.get(k), k == sel);
    }

    // ------------------------------------------------------------------ zoom / lens chips

    private void rebuildZoomChips() {
        zoomRow.removeAllViews();
        if (!front) {
            for (final CamController.CamInfo ci : cam.cameras) {
                if (ci.front || ci.id.equals(currentId)) continue;
                TextView c = chip(ci.label + " lens", new Runnable() {
                    public void run() {
                        switchCamera(ci.id);
                    }
                });
                zoomRow.addView(c, lpc(-2, -2, 3, 0, 3, 0));
            }
        }
        float[] presets = {0.5f, 1f, 2f, 3f, 5f, 10f};
        for (final float z : presets) {
            if (z < cam.minZoom - 0.01f || z > cam.maxZoom + 0.01f) continue;
            TextView c = chip(z < 1f ? String.format(Locale.US, "%.1fx", z) : String.format(Locale.US, "%.0fx", z), new Runnable() {
                public void run() {
                    setZoom(z);
                }
            });
            c.setTag(Float.valueOf(z));
            zoomRow.addView(c, lpc(-2, -2, 3, 0, 3, 0));
        }
        refreshZoomChips();
    }

    private void refreshZoomChips() {
        for (int i = 0; i < zoomRow.getChildCount(); i++) {
            View c = zoomRow.getChildAt(i);
            Object tag = c.getTag();
            if (tag instanceof Float && c instanceof TextView) {
                setChipActive((TextView) c, Math.abs(((Float) tag) - cam.s.zoom) < 0.06f);
            }
        }
    }

    private void switchCamera(String id) {
        currentId = id;
        cam.s.zoom = 1f;
        opened = true;
        cam.open(id, tv);
    }

    private void flipCamera() {
        if (busy || recordingUi) return;
        String id = front ? cam.defaultBackId() : cam.frontId();
        if (id == null) {
            toast("No second camera found");
            return;
        }
        switchCamera(id);
    }

    // ------------------------------------------------------------------ pro panel

    private LinearLayout proLine(String name, SeekBar bar, TextView val, View extra) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = new TextView(this);
        n.setText(name);
        n.setTextColor(Color.rgb(255, 204, 0));
        n.setTextSize(11);
        n.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        r.addView(n, new LinearLayout.LayoutParams(dp(54), -2));
        r.addView(bar, new LinearLayout.LayoutParams(0, -2, 1f));
        val.setTextColor(Color.WHITE);
        val.setTextSize(11);
        val.setGravity(Gravity.RIGHT);
        r.addView(val, new LinearLayout.LayoutParams(dp(82), -2));
        if (extra != null) r.addView(extra, lpc(-2, -2, 4, 0, 0, 0));
        return r;
    }

    private interface IntCb {
        void run(int p, boolean user);
    }

    private static void seek(SeekBar sb, final IntCb cb) {
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                cb.run(p, user);
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
            }
        });
    }

    private void buildProPanel() {
        proPanel = new LinearLayout(this);
        proPanel.setOrientation(LinearLayout.VERTICAL);
        proPanel.setPadding(dp(10), dp(2), dp(10), dp(6));
        proPanel.setVisibility(View.GONE);

        evBar = new SeekBar(this);
        evVal = new TextView(this);
        proPanel.addView(proLine("EV", evBar, evVal, null));
        seek(evBar, new IntCb() {
            public void run(int p, boolean user) {
                if (!user || cam.evRange == null) return;
                int idx = cam.evRange.getLower() + p;
                cam.s.evIndex = idx;
                evVal.setText(String.format(Locale.US, "%+.1f", idx * cam.evStep));
                cam.updatePreview();
            }
        });

        isoBar = new SeekBar(this);
        isoBar.setMax(100);
        isoVal = new TextView(this);
        isoAuto = chip("AUTO", new Runnable() {
            public void run() {
                cam.s.manualExp = false;
                isoVal.setText("ISO " + liveIso);
                cam.updatePreview();
            }
        });
        isoRow = proLine("ISO", isoBar, isoVal, isoAuto);
        proPanel.addView(isoRow);
        seek(isoBar, new IntCb() {
            public void run(int p, boolean user) {
                if (!user || cam.isoRange == null) return;
                beginManual();
                cam.s.iso = isoFromProgress(p);
                isoVal.setText("ISO " + cam.s.iso);
                cam.updatePreview();
            }
        });

        shutBar = new SeekBar(this);
        shutBar.setMax(100);
        shutVal = new TextView(this);
        shutRow = proLine("SHUTTER", shutBar, shutVal, null);
        proPanel.addView(shutRow);
        seek(shutBar, new IntCb() {
            public void run(int p, boolean user) {
                if (!user || cam.expRange == null) return;
                beginManual();
                cam.s.expNs = expFromProgress(p);
                shutVal.setText(fmtShutter(cam.s.expNs));
                cam.updatePreview();
            }
        });

        focusBar = new SeekBar(this);
        focusBar.setMax(100);
        focusVal = new TextView(this);
        focusAuto = chip("AF", new Runnable() {
            public void run() {
                cam.s.manualFocus = false;
                focusVal.setText("Auto");
                cam.updatePreview();
            }
        });
        focusRow = proLine("FOCUS", focusBar, focusVal, focusAuto);
        proPanel.addView(focusRow);
        seek(focusBar, new IntCb() {
            public void run(int p, boolean user) {
                if (!user || cam.minFocus <= 0f) return;
                cam.s.manualFocus = true;
                cam.s.afLock = false;
                cam.s.focusDiopter = p / 100f * cam.minFocus;
                focusVal.setText(p >= 99 ? "Macro" : (p <= 1 ? "Infinity" : String.format(Locale.US, "%.0f cm", 100f / Math.max(0.1f, cam.s.focusDiopter))));
                cam.updatePreview();
            }
        });

        wbBar = new SeekBar(this);
        wbBar.setMax(WB_NAMES.length - 1);
        wbVal = new TextView(this);
        wbVal.setText("AUTO");
        proPanel.addView(proLine("WB", wbBar, wbVal, null));
        seek(wbBar, new IntCb() {
            public void run(int p, boolean user) {
                if (!user) return;
                cam.s.awbMode = WB_MODES[p];
                wbVal.setText(WB_NAMES[p]);
                cam.updatePreview();
            }
        });

        rawChip = chip("RAW OFF", new Runnable() {
            public void run() {
                boolean on = !cam.s.wantRaw;
                cam.s.wantRaw = on;
                CamPrefs.put(CameraActivity.this, CamPrefs.RAW, on ? 1 : 0);
                rawChip.setText(on ? "RAW (DNG) ON" : "RAW OFF");
                setChipActive(rawChip, on);
                cam.createPreviewSession();
            }
        });
        proPanel.addView(rawChip, lpc(-2, -2, 0, 6, 0, 0));
    }

    private void setupProPanel() {
        if (cam.evRange != null) {
            evBar.setMax(cam.evRange.getUpper() - cam.evRange.getLower());
            evBar.setProgress(cam.s.evIndex - cam.evRange.getLower());
            evVal.setText(String.format(Locale.US, "%+.1f", cam.s.evIndex * cam.evStep));
        }
        isoRow.setVisibility(cam.manualSensor ? View.VISIBLE : View.GONE);
        shutRow.setVisibility(cam.manualSensor ? View.VISIBLE : View.GONE);
        focusRow.setVisibility(cam.minFocus > 0f ? View.VISIBLE : View.GONE);
        focusVal.setText(cam.s.manualFocus ? focusVal.getText() : "Auto");
        rawChip.setVisibility(cam.rawSupported && mode < VIDEO ? View.VISIBLE : View.GONE);
        rawChip.setText(cam.s.wantRaw ? "RAW (DNG) ON" : "RAW OFF");
        setChipActive(rawChip, cam.s.wantRaw);
    }

    /** First touch of ISO or shutter: start from what the camera is doing right now. */
    private void beginManual() {
        if (cam.s.manualExp) return;
        if (liveIso > 0) cam.s.iso = liveIso;
        if (liveExp > 0) cam.s.expNs = liveExp;
        cam.s.manualExp = true;
    }

    private int isoFromProgress(int p) {
        double lo = cam.isoRange.getLower(), hi = cam.isoRange.getUpper();
        return (int) (lo * Math.pow(hi / lo, p / 100.0));
    }

    private long maxExposure() {
        long cap = cam.s.video ? 1000000000L / Math.max(1, cam.s.fps) : 2000000000L;
        return Math.max(cam.expRange.getLower() + 1, Math.min(cam.expRange.getUpper(), cap));
    }

    private long expFromProgress(int p) {
        double lo = cam.expRange.getLower(), hi = maxExposure();
        return (long) (lo * Math.pow(hi / lo, p / 100.0));
    }

    private static String fmtShutter(long ns) {
        if (ns <= 0) return "-";
        if (ns >= 400000000L) return String.format(Locale.US, "%.1fs", ns / 1e9);
        return "1/" + Math.round(1e9 / ns);
    }

    // ------------------------------------------------------------------ shutter

    private int shotMode, shotCap;
    private boolean shotFront;

    private void onShutter() {
        if (settingsView.getVisibility() == View.VISIBLE || !opened) return;
        if (mode >= VIDEO) {
            toggleRecording();
            return;
        }
        if (busy) return;
        busy = true;
        if (timerSec > 0) countdown(timerSec);
        else takePhoto();
    }

    private void countdown(final int n) {
        if (paused) {
            timerText.setVisibility(View.GONE);
            busy = false;
            return;
        }
        if (n <= 0) {
            timerText.setVisibility(View.GONE);
            takePhoto();
            return;
        }
        timerText.setVisibility(View.VISIBLE);
        timerText.setText(String.valueOf(n));
        ui.postDelayed(new Runnable() {
            public void run() {
                countdown(n - 1);
            }
        }, 1000);
    }

    private void takePhoto() {
        shotMode = mode;
        shotFront = front;
        int cap = CamController.M_SINGLE;
        if (mode == NIGHT) cap = CamController.M_NIGHT;
        else if (mode == PHOTO && hdrOn && !cam.s.manualExp) cap = CamController.M_HDR;
        else if (mode == PHOTO && burstOn) cap = CamController.M_BURST;
        shotCap = cap;
        if (CamPrefs.on(this, CamPrefs.SOUND, true) && cap != CamController.M_NIGHT) sound.play(MediaActionSound.SHUTTER_CLICK);
        if (cap == CamController.M_NIGHT) hint("Hold still...");
        File dng = null;
        if (cap == CamController.M_SINGLE && cam.s.wantRaw && cam.rawSupported) dng = MediaSaver.newFile("IMG", "dng");
        cam.capture(cap, deviceDeg, dng, photoCb);
    }

    private final CamController.PhotoCb photoCb = new CamController.PhotoCb() {
        @Override
        public void onFrames(final List<byte[]> jpegs, final MediaSaver.ExifInfo exif, final int rotation) {
            runOnUiThread(new Runnable() {
                public void run() {
                    infoText.setText("Processing...");
                    if (CamPrefs.on(CameraActivity.this, CamPrefs.SOUND, true) && shotCap == CamController.M_NIGHT) {
                        sound.play(MediaActionSound.SHUTTER_CLICK);
                    }
                }
            });
            exec.execute(new Runnable() {
                public void run() {
                    process(jpegs, exif, rotation);
                }
            });
        }

        @Override
        public void onRaw(File f) {
            MediaSaver.scan(CameraActivity.this, f, null);
        }

        @Override
        public void onFail(final String msg) {
            runOnUiThread(new Runnable() {
                public void run() {
                    busy = false;
                    hint(null);
                    toast(msg);
                }
            });
        }
    };

    private void addGps(MediaSaver.ExifInfo exif) {
        if (!CamPrefs.on(this, CamPrefs.GEOTAG, false)) return;
        try {
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) return;
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null) {
                exif.lat = best.getLatitude();
                exif.lon = best.getLongitude();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Runs on the worker thread: merge frames, finish the picture, save, tell the gallery. */
    private void process(List<byte[]> jpegs, MediaSaver.ExifInfo exif, int rotation) {
        int cap = shotCap;
        int shot = shotMode;
        boolean selfie = shotFront;
        addGps(exif);
        File saved = null;
        Bitmap bmp = null;
        try {
            int procMode = CamPrefs.get(this, CamPrefs.PROCESSING, 0);
            if (cap == CamController.M_BURST) {
                for (byte[] j : jpegs) {
                    File f = MediaSaver.newFile("IMG", "jpg");
                    MediaSaver.writeBytes(j, f);
                    saved = f;
                    MediaSaver.scan(this, f, null);
                }
                finishPhoto(saved, null, jpegs.size() + " burst shots saved");
                return;
            }
            if (cap == CamController.M_SINGLE && procMode == 2) {
                File f = MediaSaver.newFile("IMG", "jpg");
                MediaSaver.writeBytes(jpegs.get(0), f);
                MediaSaver.writeExif(f, exif, CamController.exifOrientation(rotation));
                finishPhoto(f, null, "Saved");
                return;
            }

            int maxPx = cam.s.maxPixels;
            if (cap == CamController.M_NIGHT || cap == CamController.M_HDR) maxPx = Math.min(maxPx, 8000000);
            if (cap == CamController.M_HDR) bmp = Fusion.hdr(jpegs, maxPx);
            else if (cap == CamController.M_NIGHT) bmp = Fusion.night(jpegs, maxPx);
            else bmp = Engine.decode(jpegs.get(0), maxPx);
            if (bmp == null) throw new IllegalStateException("decode failed");

            boolean mirrorSave = selfie && CamPrefs.on(this, CamPrefs.MIRROR_SAVE, false);
            bmp = Engine.upright(bmp, rotation, mirrorSave, cam.s.aspect == 2);

            if (shot == PORTRAIT) {
                float[] blurs = {0.25f, 0.55f, 0.9f};
                float blur = blurs[Math.max(0, Math.min(2, CamPrefs.get(this, CamPrefs.PORTRAIT_BLUR, 1)))];
                Bitmap p = Engine.portrait(bmp, CamPrefs.get(this, CamPrefs.PORTRAIT_FX, 0), blur);
                if (p != bmp) bmp.recycle();
                bmp = p;
            }

            Engine.Params p = new Engine.Params();
            p.style = CamPrefs.get(this, CamPrefs.STYLE, 0);
            p.look = CamPrefs.get(this, CamPrefs.LOOK, 0);
            p.fast = procMode == 1;
            if (shot == NIGHT) {
                p.gamma = 0.80f;
                p.shadowBoost = 0.35f;
                p.denoiseBoost = 0.55f;
            } else if (cap == CamController.M_HDR) {
                p.strength = 0.75f;
            }
            if (selfie && (shot == PHOTO || shot == PORTRAIT)) {
                p.beauty = CamPrefs.get(this, CamPrefs.BEAUTY, 0) * 0.3f;
            }
            Engine.enhance(bmp, p);
            if (CamPrefs.on(this, CamPrefs.WATERMARK, false)) {
                Engine.watermark(bmp, "Shot on " + Build.MODEL + "  |  ProCam");
            }
            int[] qs = {85, 92, 97};
            int q = qs[Math.max(0, Math.min(2, CamPrefs.get(this, CamPrefs.JPEGQ, 1)))];
            File f = MediaSaver.newFile("IMG", "jpg");
            MediaSaver.saveJpeg(bmp, f, q);
            MediaSaver.writeExif(f, exif, android.media.ExifInterface.ORIENTATION_NORMAL);
            int tw = 120;
            Bitmap th = Bitmap.createScaledBitmap(bmp, tw, Math.max(1, tw * bmp.getHeight() / bmp.getWidth()), true);
            MediaSaver.scan(this, f, null);
            finishPhoto(f, th, "Saved");
        } catch (OutOfMemoryError oom) {
            // not enough memory for the full pipeline: still keep the picture
            try {
                File f = MediaSaver.newFile("IMG", "jpg");
                MediaSaver.writeBytes(jpegs.get(0), f);
                MediaSaver.writeExif(f, exif, CamController.exifOrientation(rotation));
                MediaSaver.scan(this, f, null);
                finishPhoto(f, null, "Low memory: saved without processing");
            } catch (Throwable t) {
                finishPhoto(null, null, "Could not save (low memory)");
            }
        } catch (Throwable t) {
            finishPhoto(null, null, "Could not save: " + t.getMessage());
        } finally {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        }
    }

    private void finishPhoto(final File f, final Bitmap th, final String msg) {
        if (f != null) {
            MediaSaver.scan(this, f, new MediaSaver.ScanDone() {
                public void done(Uri u) {
                    lastUri = u;
                    lastIsVideo = false;
                }
            });
        }
        runOnUiThread(new Runnable() {
            public void run() {
                busy = false;
                if (th != null) thumb.setImageBitmap(th);
                hint(null);
                if (f == null || !msg.equals("Saved")) toast(msg);
            }
        });
    }

    // ------------------------------------------------------------------ video

    private void toggleRecording() {
        if (recordingUi) {
            cam.stopVideo();
            if (CamPrefs.on(this, CamPrefs.SOUND, true)) sound.play(MediaActionSound.STOP_VIDEO_RECORDING);
            return;
        }
        if (busy) return;
        boolean slow = mode == SLOMO;
        boolean lapse = mode == LAPSE;
        if (slow && !cam.highSpeedSupported) {
            toast("This phone's camera has no 120/240 fps slow-motion mode");
            return;
        }
        busy = true;
        if (CamPrefs.on(this, CamPrefs.SOUND, true)) sound.play(MediaActionSound.START_VIDEO_RECORDING);
        File f = MediaSaver.newFile("VID", "mp4");
        recordingUi = true;
        recStart = System.currentTimeMillis();
        shutter.set(true, true);
        pauseChip.setText("PAUSE");
        pauseChip.setVisibility(Build.VERSION.SDK_INT >= 24 && !lapse && !slow ? View.VISIBLE : View.GONE);
        if (!hasMic() && !lapse && !slow) toast("Microphone not allowed - recording without sound");
        cam.startVideo(f, deviceDeg, hasMic(), lapse ? lapseSec : 0, slow);
    }

    private void finishVideo(final File f, final int slowFactor) {
        exec.execute(new Runnable() {
            public void run() {
                File out = f;
                if (slowFactor > 1) {
                    File slow = MediaSaver.newFile("VID_SLOWMO", "mp4");
                    if (MediaSaver.slowMotion(f, slow, slowFactor)) {
                        f.delete();
                        out = slow;
                    }
                }
                final File fin = out;
                Bitmap t = null;
                try {
                    MediaMetadataRetriever r = new MediaMetadataRetriever();
                    r.setDataSource(fin.getAbsolutePath());
                    Bitmap fr = r.getFrameAtTime(0);
                    r.release();
                    if (fr != null) t = Bitmap.createScaledBitmap(fr, 120, Math.max(1, 120 * fr.getHeight() / fr.getWidth()), true);
                } catch (Throwable ignored) {
                }
                final Bitmap th = t;
                MediaSaver.scan(CameraActivity.this, fin, new MediaSaver.ScanDone() {
                    public void done(Uri u) {
                        lastUri = u;
                        lastIsVideo = true;
                    }
                });
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (th != null) thumb.setImageBitmap(th);
                        toast("Video saved: " + fin.getName());
                    }
                });
            }
        });
    }

    private void openLast() {
        if (lastUri == null) {
            toast("Take a picture first");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(lastUri, lastIsVideo ? "video/*" : "image/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Throwable t) {
            toast("No gallery app found");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ settings screen

    private LinearLayout settingsList;
    private TextView capsText;

    private void buildSettings() {
        settingsView = new ScrollView(this);
        settingsView.setBackgroundColor(Color.rgb(18, 18, 18));
        settingsView.setVisibility(View.GONE);
        settingsList = new LinearLayout(this);
        settingsList.setOrientation(LinearLayout.VERTICAL);
        settingsList.setPadding(dp(16), dp(24), dp(16), dp(24));
        settingsView.addView(settingsList, new ViewGroup.LayoutParams(-1, -2));
        root.addView(settingsView, new FrameLayout.LayoutParams(-1, -1));

        TextView title = new TextView(this);
        title.setText("Camera settings");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        settingsList.addView(title);
        settingsList.addView(chip("DONE", new Runnable() {
            public void run() {
                settingsView.setVisibility(View.GONE);
            }
        }), lpc(-2, -2, 0, 8, 0, 8));

        section("Look and feel");
        addSetting("Camera style", CamPrefs.STYLE, new String[]{"iPhone Style", "Galaxy Style", "Natural"}, 0, false);
        addSetting("Photo processing", CamPrefs.PROCESSING, new String[]{"Full (best)", "Fast (weak phones)", "Off (straight from sensor)"}, 0, false);
        addSetting("Portrait blur", CamPrefs.PORTRAIT_BLUR, new String[]{"Low", "Medium", "High"}, 1, false);
        addSetting("Smooth skin on selfies", CamPrefs.BEAUTY, new String[]{"Off", "Low", "Medium", "High"}, 0, false);

        section("Photo");
        addSetting("Photo size", CamPrefs.MAXMP, new String[]{"Auto", "12 MP", "8 MP", "5 MP", "Maximum"}, 0, true);
        addSetting("JPEG quality", CamPrefs.JPEGQ, new String[]{"Standard", "High", "Maximum"}, 1, false);
        addSetting("Save RAW (DNG) when possible", CamPrefs.RAW, new String[]{"Off", "On"}, 0, true);
        addSetting("Grid", CamPrefs.GRID, new String[]{"Off", "3 x 3", "Golden ratio", "Diagonals"}, 1, false);
        addSetting("Level (horizon line)", CamPrefs.LEVEL, new String[]{"Off", "On"}, 1, false);
        addSetting("Watermark", CamPrefs.WATERMARK, new String[]{"Off", "On"}, 0, false);
        addSetting("Location tag (GPS)", CamPrefs.GEOTAG, new String[]{"Off", "On"}, 0, false);
        addSetting("Shutter sound", CamPrefs.SOUND, new String[]{"Off", "On"}, 1, false);
        addSetting("Mirror selfie preview", CamPrefs.MIRROR, new String[]{"Off", "On"}, 1, false);
        addSetting("Mirror selfie photo", CamPrefs.MIRROR_SAVE, new String[]{"Off", "On"}, 0, false);

        section("Video");
        addSetting("Video quality", CamPrefs.VIDRES, new String[]{"Best", "4K", "1080p", "720p"}, 0, true);
        addSetting("Frame rate", CamPrefs.VIDFPS, new String[]{"30 fps", "24 fps", "60 fps"}, 0, false);
        addSetting("Video format", CamPrefs.HEVC, new String[]{"H.264 (compatible)", "HEVC (smaller files)"}, 0, false);
        addSetting("Video stabilisation", CamPrefs.STAB, new String[]{"Off", "On"}, 1, false);
        addSetting("Cinematic profile", CamPrefs.CINE, new String[]{"Film grade", "Flat (log-like, for editing)"}, 0, false);

        section("This phone");
        capsText = new TextView(this);
        capsText.setTextColor(Color.rgb(190, 190, 190));
        capsText.setTextSize(12);
        settingsList.addView(capsText);
        settingsView.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) capsText.setText(capabilities());
                return false;
            }
        });
    }

    private void section(String name) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextColor(Color.rgb(255, 204, 0));
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, dp(18), 0, dp(4));
        settingsList.addView(t);
    }

    private void addSetting(String label, final String key, final String[] options, final int def, final boolean rebuild) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Color.WHITE);
        l.setTextSize(14);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView b = chip(options[Math.min(options.length - 1, CamPrefs.get(this, key, def))], null);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                int next = (CamPrefs.get(CameraActivity.this, key, def) + 1) % options.length;
                CamPrefs.put(CameraActivity.this, key, next);
                b.setText(options[next]);
                applySettings(rebuild);
                if (key.equals(CamPrefs.STYLE)) {
                    updatePreviewLook();
                    buildLooksRow();
                }
            }
        });
        row.addView(b);
        settingsList.addView(row);
    }

    private int autoMaxPixels() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        int mc = am == null ? 128 : am.getMemoryClass();
        if (mc >= 192) return 12000000;
        if (mc >= 128) return 8000000;
        return 5000000;
    }

    private void applySettings(boolean rebuild) {
        int mp = CamPrefs.get(this, CamPrefs.MAXMP, 0);
        int[] table = {0, 12000000, 8000000, 5000000, 200000000};
        cam.s.maxPixels = mp == 0 ? autoMaxPixels() : table[Math.min(4, mp)];
        cam.s.wantRaw = CamPrefs.on(this, CamPrefs.RAW, false);
        cam.s.videoQuality = CamPrefs.get(this, CamPrefs.VIDRES, 0);
        cam.s.hevc = CamPrefs.on(this, CamPrefs.HEVC, false);
        cam.s.stab = CamPrefs.on(this, CamPrefs.STAB, true) || mode == CINEMA;
        cam.s.tone = mode == CINEMA ? (CamPrefs.get(this, CamPrefs.CINE, 0) == 0 ? CamController.T_CINE : CamController.T_FLAT)
                : CamController.T_NONE;
        grid.mode = CamPrefs.get(this, CamPrefs.GRID, 1);
        grid.invalidate();
        level.enabled = CamPrefs.on(this, CamPrefs.LEVEL, true);
        level.invalidate();
        tv.setScaleX(front && CamPrefs.on(this, CamPrefs.MIRROR, true) ? -1f : 1f);
        applyFps();
        if (rebuild && opened) cam.createPreviewSession();
    }

    private String capabilities() {
        StringBuilder sb = new StringBuilder();
        sb.append("Cameras found: ").append(cam.cameras.size()).append("\n");
        for (CamController.CamInfo ci : cam.cameras) {
            sb.append(" - ").append(ci.front ? "front" : "back").append(" #").append(ci.id);
            if (ci.eqFocal > 0) sb.append("  ~").append(Math.round(ci.eqFocal)).append(" mm equivalent");
            sb.append("\n");
        }
        sb.append("\nActive camera:\n");
        sb.append(" Manual ISO / shutter: ").append(cam.manualSensor ? "yes" : "no").append("\n");
        sb.append(" Manual focus: ").append(cam.minFocus > 0 ? "yes" : "no (fixed focus)").append("\n");
        sb.append(" RAW (DNG): ").append(cam.rawSupported ? "yes" : "no").append("\n");
        sb.append(" Slow motion 120/240 fps: ").append(cam.highSpeedSupported ? "yes" : "no").append("\n");
        sb.append(" Flash: ").append(cam.hasFlash ? "yes" : "no").append("\n");
        sb.append(String.format(Locale.US, " Zoom range: %.1fx - %.1fx\n", cam.minZoom, cam.maxZoom));
        sb.append("\nFeatures that this phone's camera does not offer are hidden or switched off automatically.");
        return sb.toString();
    }
}
