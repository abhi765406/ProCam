package com.procam.app;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.params.TonemapCurve;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Everything Camera2: opening cameras, preview, manual controls, still capture
 * (single / HDR bracket / night burst / burst / RAW), and video (normal,
 * cinematic, slow motion, time-lapse).
 */
final class CamController {

    // ------------------------------------------------------------------ public types

    interface Listener {
        void onSession(boolean video, int previewW, int previewH);
        void onError(String msg);
        void onResult(CaptureResult r);
        void onVideoDone(File f, int slowFactor, String error);
    }

    interface PhotoCb {
        void onFrames(List<byte[]> jpegs, MediaSaver.ExifInfo exif, int rotation);
        void onRaw(File f);
        void onFail(String msg);
    }

    static final class Settings {
        int flash = 0;              // 0 off, 1 auto, 2 on, 3 torch
        int evIndex = 0;
        float zoom = 1f;
        boolean aeLock, afLock;
        boolean manualExp;
        int iso = 100;
        long expNs = 10000000L;
        boolean manualFocus;
        float focusDiopter = 0f;
        int awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO;
        int aspect = 0;             // 0 = 4:3, 1 = 16:9, 2 = 1:1 (cropped from 4:3)
        int maxPixels = 12000000;
        boolean wantRaw;
        boolean video;
        int fps = 30;
        int videoQuality = 0;       // 0 best, 1 = 4K, 2 = 1080p, 3 = 720p
        boolean hevc;
        boolean stab = true;
        int tone = 0;               // 0 none, 1 cinematic grade, 2 flat
        MeteringRectangle[] regions;
    }

    static final class CamInfo {
        String id;
        boolean front;
        float eqFocal;
        String label;
    }

    static final int M_SINGLE = 0, M_HDR = 1, M_NIGHT = 2, M_BURST = 3;
    static final int T_NONE = 0, T_CINE = 1, T_FLAT = 2;

    // ------------------------------------------------------------------ state

    final Settings s = new Settings();
    final List<CamInfo> cameras = new ArrayList<CamInfo>();

    // capabilities of the open camera (read by the UI)
    boolean hasFlash, rawSupported, manualSensor, highSpeedSupported, front;
    float minZoom = 1f, maxZoom = 1f, minFocus = 0f, evStep = 0.33f;
    Range<Integer> isoRange, evRange;
    Range<Long> expRange;
    int sensorOrientation = 90;
    String camId;
    float eqFocal;
    List<Size> videoSizes = new ArrayList<Size>();

    private final Context ctx;
    private final CameraManager mgr;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler bg;

    private CameraDevice device;
    private CameraCaptureSession session;
    private CameraCharacteristics chars;
    private CaptureRequest.Builder previewB;
    private TextureView tv;
    private Surface previewSurface;
    private ImageReader jpegReader, rawReader;
    private Size photoSize, previewSize, rawSize;
    private boolean useZoomRatio;
    private Rect active;
    private volatile CaptureResult lastResult;
    private boolean rawBroken;

    private MediaRecorder recorder;
    private File videoFile;
    boolean recording;
    private boolean recordingHighSpeed;
    private int recordingSlowFactor = 1;

    // photo capture bookkeeping
    private final Object lock = new Object();
    private final List<byte[]> frames = new ArrayList<byte[]>();
    private int expected;
    private PhotoCb photoCb;
    private MediaSaver.ExifInfo pendingExif;
    private int pendingRotation;
    private Image pendingRaw;
    private TotalCaptureResult pendingRawResult;
    private File dngTarget;

    CamController(Context c, Listener l) {
        ctx = c.getApplicationContext();
        mgr = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        listener = l;
        enumerate();
    }

    // ------------------------------------------------------------------ cameras

    private void enumerate() {
        cameras.clear();
        try {
            for (String id : mgr.getCameraIdList()) {
                CameraCharacteristics cc = mgr.getCameraCharacteristics(id);
                int[] caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                if (caps != null && !contains(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) continue;
                StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null || map.getOutputSizes(ImageFormat.JPEG) == null) continue;
                CamInfo ci = new CamInfo();
                ci.id = id;
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                ci.front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
                ci.eqFocal = equivalentFocal(cc);
                cameras.add(ci);
            }
        } catch (Exception ignored) {
        }
        // label lenses relative to the main back camera (closest to ~26 mm equivalent)
        float main26 = 0f;
        float bestDiff = 1e9f;
        for (CamInfo ci : cameras) {
            if (ci.front || ci.eqFocal <= 0) continue;
            float d = Math.abs(ci.eqFocal - 26f);
            if (d < bestDiff) {
                bestDiff = d;
                main26 = ci.eqFocal;
            }
        }
        for (CamInfo ci : cameras) {
            if (ci.front) {
                ci.label = "Selfie";
            } else if (main26 > 0 && ci.eqFocal > 0) {
                float r = ci.eqFocal / main26;
                ci.label = Math.abs(r - 1f) < 0.12f ? "1x" : (r < 1f ? String.format("%.1fx", r) : String.format("%.0fx", r));
            } else {
                ci.label = "Cam " + ci.id;
            }
        }
    }

    private static float equivalentFocal(CameraCharacteristics cc) {
        try {
            float[] fl = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            android.util.SizeF ps = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (fl == null || fl.length == 0 || ps == null) return 0f;
            double diag = Math.hypot(ps.getWidth(), ps.getHeight());
            return (float) (fl[0] * 43.27 / diag);
        } catch (Exception e) {
            return 0f;
        }
    }

    String defaultBackId() {
        float bestDiff = 1e9f;
        String best = null;
        for (CamInfo ci : cameras) {
            if (ci.front) continue;
            float d = ci.eqFocal > 0 ? Math.abs(ci.eqFocal - 26f) : 500f;
            if (best == null || d < bestDiff) {
                best = ci.id;
                bestDiff = d;
            }
        }
        if (best == null && !cameras.isEmpty()) best = cameras.get(0).id;
        return best;
    }

    String frontId() {
        for (CamInfo ci : cameras) if (ci.front) return ci.id;
        return null;
    }

    // ------------------------------------------------------------------ open / close

    void open(final String id, final TextureView view) {
        close();
        thread = new HandlerThread("cam");
        thread.start();
        bg = new Handler(thread.getLooper());
        camId = id;
        tv = view;
        try {
            chars = mgr.getCameraCharacteristics(id);
            readCapabilities();
            mgr.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    device = d;
                    createPreviewSession();
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    d.close();
                    device = null;
                    fail("Camera disconnected");
                }

                @Override
                public void onError(CameraDevice d, int error) {
                    d.close();
                    device = null;
                    fail("Camera error " + error + " (is another app using the camera?)");
                }
            }, bg);
        } catch (SecurityException e) {
            fail("Camera permission missing");
        } catch (Exception e) {
            fail("Could not open camera: " + e.getMessage());
        }
    }

    void close() {
        try {
            if (recording) stopVideoInternal(false);
        } catch (Throwable ignored) {
        }
        try {
            if (session != null) session.close();
        } catch (Throwable ignored) {
        }
        session = null;
        try {
            if (device != null) device.close();
        } catch (Throwable ignored) {
        }
        device = null;
        closeReaders();
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        bg = null;
        lastResult = null;
    }

    private void closeReaders() {
        try {
            if (jpegReader != null) jpegReader.close();
        } catch (Throwable ignored) {
        }
        try {
            synchronized (lock) {
                if (pendingRaw != null) pendingRaw.close();
                pendingRaw = null;
            }
            if (rawReader != null) rawReader.close();
        } catch (Throwable ignored) {
        }
        jpegReader = null;
        rawReader = null;
    }

    private void fail(final String msg) {
        main.post(new Runnable() {
            public void run() {
                listener.onError(msg);
            }
        });
    }

    private void readCapabilities() {
        active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        sensorOrientation = so == null ? 90 : so;
        Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
        front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        Boolean fl = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        hasFlash = fl != null && fl;
        Float mf = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        minFocus = mf == null ? 0f : mf;
        isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        android.util.Rational step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        evStep = step == null ? 0.33f : step.floatValue();
        int[] caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        manualSensor = caps != null && contains(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                && isoRange != null && expRange != null;
        rawSupported = caps != null && contains(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) && !rawBroken;
        highSpeedSupported = caps != null
                && contains(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO);
        eqFocal = equivalentFocal(chars);

        useZoomRatio = false;
        minZoom = 1f;
        Float maxDig = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        maxZoom = maxDig == null ? 1f : maxDig;
        if (Build.VERSION.SDK_INT >= 30) {
            Range<Float> zr = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (zr != null) {
                useZoomRatio = true;
                minZoom = zr.getLower();
                maxZoom = zr.getUpper();
            }
        }
        if (maxZoom > 10f) maxZoom = 10f;
        if (s.zoom < minZoom) s.zoom = minZoom;
        if (s.zoom > maxZoom) s.zoom = maxZoom;
        s.regions = null;
        s.afLock = false;
        s.aeLock = false;
        s.manualExp = s.manualExp && manualSensor;
        s.manualFocus = s.manualFocus && minFocus > 0f;

        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        videoSizes.clear();
        if (map != null) {
            Size[] vs = map.getOutputSizes(MediaRecorder.class);
            if (vs != null) Collections.addAll(videoSizes, vs);
            Size[] rs = map.getOutputSizes(ImageFormat.RAW_SENSOR);
            rawSize = null;
            if (rs != null && rs.length > 0) {
                rawSize = rs[0];
                for (Size z : rs) if ((long) z.getWidth() * z.getHeight() > (long) rawSize.getWidth() * rawSize.getHeight()) rawSize = z;
            } else {
                rawSupported = false;
            }
        }
    }

    // ------------------------------------------------------------------ sizes

    private Size choosePhotoSize() {
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] all = map.getOutputSizes(ImageFormat.JPEG);
        double target = s.aspect == 1 ? 16.0 / 9.0 : 4.0 / 3.0;
        Size best = null, smallestOk = null;
        for (Size z : all) {
            double r = (double) z.getWidth() / z.getHeight();
            if (Math.abs(r - target) > 0.03) continue;
            long area = (long) z.getWidth() * z.getHeight();
            if (smallestOk == null || area < (long) smallestOk.getWidth() * smallestOk.getHeight()) smallestOk = z;
            if (area <= s.maxPixels && (best == null || area > (long) best.getWidth() * best.getHeight())) best = z;
        }
        if (best == null) best = smallestOk;
        if (best == null) {
            best = all[0];
            for (Size z : all) if ((long) z.getWidth() * z.getHeight() > (long) best.getWidth() * best.getHeight()) best = z;
        }
        return best;
    }

    private Size choosePreviewSize(double targetRatio, long maxArea) {
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] all = map.getOutputSizes(SurfaceTexture.class);
        Size best = null;
        for (Size z : all) {
            double r = (double) z.getWidth() / z.getHeight();
            long area = (long) z.getWidth() * z.getHeight();
            if (Math.abs(r - targetRatio) > 0.03 || area > maxArea) continue;
            if (best == null || area > (long) best.getWidth() * best.getHeight()) best = z;
        }
        if (best == null) {
            double diff = 1e9;
            for (Size z : all) {
                double d = Math.abs((double) z.getWidth() / z.getHeight() - targetRatio);
                long area = (long) z.getWidth() * z.getHeight();
                if (area <= maxArea && d < diff) {
                    diff = d;
                    best = z;
                }
            }
        }
        return best == null ? all[all.length - 1] : best;
    }

    /** Sizes the user can choose for video: 4K / 1080p / 720p when this camera can record them. */
    Size videoSizeFor(int quality) {
        int[][] wanted = {{3840, 2160}, {1920, 1080}, {1280, 720}};
        List<Size> avail = new ArrayList<Size>();
        for (int[] w : wanted) {
            for (Size z : videoSizes) {
                if (z.getWidth() == w[0] && z.getHeight() == w[1]) {
                    avail.add(z);
                    break;
                }
            }
        }
        if (avail.isEmpty()) {
            // fall back to the biggest 16:9 or 4:3 size not above 1080p
            Size b = null;
            for (Size z : videoSizes) {
                long a = (long) z.getWidth() * z.getHeight();
                if (a <= 1920L * 1080L && (b == null || a > (long) b.getWidth() * b.getHeight())) b = z;
            }
            return b;
        }
        if (quality == 1) return pick(avail, 3840);
        if (quality == 2) return pick(avail, 1920);
        if (quality == 3) return pick(avail, 1280);
        return avail.get(0); // best
    }

    private static Size pick(List<Size> avail, int width) {
        for (Size z : avail) if (z.getWidth() == width) return z;
        // requested size missing: closest one that exists
        Size best = avail.get(0);
        for (Size z : avail) {
            if (Math.abs(z.getWidth() - width) < Math.abs(best.getWidth() - width)) best = z;
        }
        return best;
    }

    // ------------------------------------------------------------------ preview session

    /** (Re)builds the viewfinder session for the current mode (photo or video). */
    void createPreviewSession() {
        if (bg == null) return;
        bg.post(new Runnable() {
            public void run() {
                try {
                    createPreviewSessionNow();
                } catch (Throwable t) {
                    fail("Preview failed: " + t.getMessage());
                }
            }
        });
    }

    private void createPreviewSessionNow() throws CameraAccessException {
        if (device == null || tv == null) return;
        SurfaceTexture st = tv.getSurfaceTexture();
        if (st == null) return;
        closeSessionQuietly();
        closeReaders();

        List<Surface> surfaces = new ArrayList<Surface>();
        if (s.video) {
            Size vs = videoSizeFor(s.videoQuality);
            if (vs == null) vs = previewSize != null ? previewSize : choosePreviewSize(16.0 / 9.0, 1920L * 1080L);
            previewSize = choosePreviewSize((double) vs.getWidth() / vs.getHeight(), 1920L * 1080L);
        } else {
            photoSize = choosePhotoSize();
            previewSize = choosePreviewSize((double) photoSize.getWidth() / photoSize.getHeight(), 1920L * 1440L);
        }
        st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        if (previewSurface != null) previewSurface.release();
        previewSurface = new Surface(st);
        surfaces.add(previewSurface);

        boolean withRaw = false;
        if (!s.video) {
            jpegReader = ImageReader.newInstance(photoSize.getWidth(), photoSize.getHeight(), ImageFormat.JPEG, 4);
            jpegReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    onJpeg(r);
                }
            }, bg);
            surfaces.add(jpegReader.getSurface());
            if (s.wantRaw && rawSupported && rawSize != null) {
                rawReader = ImageReader.newInstance(rawSize.getWidth(), rawSize.getHeight(), ImageFormat.RAW_SENSOR, 2);
                rawReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                    @Override
                    public void onImageAvailable(ImageReader r) {
                        onRawImage(r);
                    }
                }, bg);
                surfaces.add(rawReader.getSurface());
                withRaw = true;
            }
        }
        final boolean triedRaw = withRaw;
        device.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession cs) {
                if (device == null) return;
                session = cs;
                startRepeating();
                final boolean v = s.video;
                final int pw = previewSize.getWidth(), ph = previewSize.getHeight();
                main.post(new Runnable() {
                    public void run() {
                        listener.onSession(v, pw, ph);
                    }
                });
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession cs) {
                if (triedRaw) {
                    // This phone cannot stream preview + JPEG + RAW together: continue without RAW.
                    rawBroken = true;
                    rawSupported = false;
                    s.wantRaw = false;
                    createPreviewSession();
                } else {
                    fail("Could not start the viewfinder");
                }
            }
        }, bg);
    }

    private void closeSessionQuietly() {
        try {
            if (session != null) {
                session.stopRepeating();
                session.close();
            }
        } catch (Throwable ignored) {
        }
        session = null;
    }

    /** Re-sends the repeating preview request with the latest settings. */
    void updatePreview() {
        if (bg == null) return;
        bg.post(new Runnable() {
            public void run() {
                startRepeating();
            }
        });
    }

    private void startRepeating() {
        try {
            if (device == null || session == null || previewSurface == null || recording) return;
            previewB = device.createCaptureRequest(s.video ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
            previewB.addTarget(previewSurface);
            applyCommon(previewB, false);
            session.setRepeatingRequest(previewB.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession cs, CaptureRequest rq, TotalCaptureResult result) {
                    lastResult = result;
                    listener.onResult(result);
                }
            }, bg);
        } catch (Exception e) {
            // session closing while we update: ignore
        }
    }

    // ------------------------------------------------------------------ request settings

    private void applyCommon(CaptureRequest.Builder b, boolean still) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        applyZoom(b);

        // ---- focus
        int[] afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (s.manualFocus && minFocus > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, Math.max(0f, Math.min(minFocus, s.focusDiopter)));
        } else if (afModes != null) {
            int mode = CameraMetadata.CONTROL_AF_MODE_OFF;
            if (s.afLock && contains(afModes, CameraMetadata.CONTROL_AF_MODE_AUTO)) {
                mode = CameraMetadata.CONTROL_AF_MODE_AUTO;
            } else if (s.video && contains(afModes, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                mode = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
            } else if (contains(afModes, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                mode = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
            } else if (contains(afModes, CameraMetadata.CONTROL_AF_MODE_AUTO)) {
                mode = CameraMetadata.CONTROL_AF_MODE_AUTO;
            }
            b.set(CaptureRequest.CONTROL_AF_MODE, mode);
        }
        if (s.regions != null) {
            Integer maxAf = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            Integer maxAe = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
            if (maxAf != null && maxAf > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, s.regions);
            if (maxAe != null && maxAe > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, s.regions);
        }

        // ---- exposure
        if (s.manualExp && manualSensor) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, clampInt(s.iso, isoRange));
            long exp = s.expNs;
            exp = Math.max(expRange.getLower(), Math.min(expRange.getUpper(), exp));
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp);
            if (s.video) b.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(exp, 1000000000L / Math.max(1, s.fps)));
        } else {
            int ae = CameraMetadata.CONTROL_AE_MODE_ON;
            if (hasFlash && still && s.flash == 1) ae = CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH;
            if (hasFlash && still && s.flash == 2) ae = CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
            b.set(CaptureRequest.CONTROL_AE_MODE, ae);
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, s.evIndex);
            Boolean aeLockOk = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE);
            if (aeLockOk != null && aeLockOk) b.set(CaptureRequest.CONTROL_AE_LOCK, s.aeLock);
        }
        if (s.video) {
            Range<Integer> r = fpsRange(s.fps);
            if (r != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r);
        }
        if (hasFlash) {
            b.set(CaptureRequest.FLASH_MODE, s.flash == 3 ? CameraMetadata.FLASH_MODE_TORCH : CameraMetadata.FLASH_MODE_OFF);
        }

        // ---- white balance
        b.set(CaptureRequest.CONTROL_AWB_MODE, s.awbMode);

        // ---- image quality: best denoise/sharpening for stills, fast ones for live view
        int[] nr = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
        if (nr != null) {
            int want = still ? CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY : CameraMetadata.NOISE_REDUCTION_MODE_FAST;
            if (contains(nr, want)) b.set(CaptureRequest.NOISE_REDUCTION_MODE, want);
        }
        int[] edge = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);
        if (edge != null) {
            int want = still ? CameraMetadata.EDGE_MODE_HIGH_QUALITY : CameraMetadata.EDGE_MODE_FAST;
            if (contains(edge, want)) b.set(CaptureRequest.EDGE_MODE, want);
        }

        // ---- stabilisation
        int[] ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if (ois != null && contains(ois, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
        }
        if (s.video && s.stab) {
            int[] vs = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            if (vs != null && contains(vs, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON);
            }
        }

        // ---- cinematic tone curve, applied inside the camera's own image pipeline
        if (s.video && s.tone != T_NONE) applyTone(b);
    }

    private void applyTone(CaptureRequest.Builder b) {
        try {
            int[] modes = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES);
            Integer maxPts = chars.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS);
            if (modes == null || !contains(modes, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)) return;
            float[] pts = s.tone == T_FLAT
                    ? new float[]{0f, 0.08f, 0.25f, 0.28f, 0.5f, 0.5f, 0.75f, 0.72f, 1f, 0.93f}
                    : new float[]{0f, 0.03f, 0.12f, 0.10f, 0.30f, 0.27f, 0.50f, 0.50f, 0.70f, 0.74f, 0.88f, 0.91f, 1f, 0.97f};
            if (maxPts != null && pts.length / 2 > maxPts) return;
            b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE);
            TonemapCurve curve = new TonemapCurve(pts, pts, pts);
            b.set(CaptureRequest.TONEMAP_CURVE, curve);
        } catch (Throwable ignored) {
        }
    }

    private void applyZoom(CaptureRequest.Builder b) {
        float z = Math.max(minZoom, Math.min(maxZoom, s.zoom));
        if (useZoomRatio && Build.VERSION.SDK_INT >= 30) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z);
        } else if (active != null && z > 1.001f) {
            int cw = (int) (active.width() / z), ch = (int) (active.height() / z);
            int x = active.centerX() - cw / 2, y = active.centerY() - ch / 2;
            b.set(CaptureRequest.SCALER_CROP_REGION, new Rect(x, y, x + cw, y + ch));
        }
    }

    private Range<Integer> fpsRange(int fps) {
        Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) return null;
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (r.getLower() == fps && r.getUpper() == fps) return r;
            if (r.getUpper() == fps && r.getLower() <= fps && (best == null || r.getLower() > best.getLower())) best = r;
        }
        return best;
    }

    boolean supportsFps(int fps) {
        return fpsRange(fps) != null;
    }

    // ------------------------------------------------------------------ tap to focus

    /**
     * u,v are the touch position in the upright picture (0..1). mirrored = the viewfinder is shown mirrored.
     */
    void focusAt(float u, float v, boolean lock, boolean mirrored) {
        if (bg == null || active == null) return;
        if (mirrored) u = 1f - u;
        float sx, sy;
        switch (sensorOrientation) {
            case 90: sx = v; sy = 1f - u; break;
            case 270: sx = 1f - v; sy = u; break;
            case 180: sx = 1f - u; sy = 1f - v; break;
            default: sx = u; sy = v;
        }
        Rect crop = active;
        if (!useZoomRatio && s.zoom > 1.001f) {
            int cw = (int) (active.width() / s.zoom), ch = (int) (active.height() / s.zoom);
            crop = new Rect(active.centerX() - cw / 2, active.centerY() - ch / 2,
                    active.centerX() + cw / 2, active.centerY() + ch / 2);
        } else if (useZoomRatio && s.zoom > 1.001f) {
            int cw = (int) (active.width() / s.zoom), ch = (int) (active.height() / s.zoom);
            crop = new Rect(active.centerX() - cw / 2, active.centerY() - ch / 2,
                    active.centerX() + cw / 2, active.centerY() + ch / 2);
        }
        int px = crop.left + (int) (sx * crop.width());
        int py = crop.top + (int) (sy * crop.height());
        int half = Math.max(30, Math.min(active.width(), active.height()) / 12);
        int l = Math.max(active.left, px - half), t = Math.max(active.top, py - half);
        int r = Math.min(active.right - 1, px + half), bt = Math.min(active.bottom - 1, py + half);
        s.regions = new MeteringRectangle[]{new MeteringRectangle(l, t, Math.max(1, r - l), Math.max(1, bt - t), 999)};
        s.afLock = lock;
        s.aeLock = lock;
        final boolean doTrigger = !s.manualFocus;
        bg.post(new Runnable() {
            public void run() {
                try {
                    if (device == null || session == null || previewSurface == null || recording) return;
                    if (doTrigger) {
                        CaptureRequest.Builder t2 = device.createCaptureRequest(
                                s.video ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
                        t2.addTarget(previewSurface);
                        applyCommon(t2, false);
                        t2.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
                        t2.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                        session.capture(t2.build(), null, bg);
                    }
                    startRepeating();
                } catch (Exception ignored) {
                }
            }
        });
    }

    void clearFocusLock() {
        s.afLock = false;
        s.aeLock = false;
        s.regions = null;
        updatePreview();
    }

    // ------------------------------------------------------------------ stills

    int jpegRotation(int deviceDegrees) {
        return front ? (sensorOrientation - deviceDegrees + 360) % 360 : (sensorOrientation + deviceDegrees) % 360;
    }

    static int exifOrientation(int degrees) {
        switch (degrees) {
            case 90: return ExifInterface.ORIENTATION_ROTATE_90;
            case 180: return ExifInterface.ORIENTATION_ROTATE_180;
            case 270: return ExifInterface.ORIENTATION_ROTATE_270;
            default: return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    void capture(final int mode, final int deviceDegrees, final File dng, final PhotoCb cb) {
        if (bg == null) {
            cb.onFail("Camera not ready");
            return;
        }
        rawListener = cb;
        final boolean needsFlash = mode == M_SINGLE && hasFlash && (s.flash == 1 || s.flash == 2) && !s.manualExp;
        bg.post(new Runnable() {
            public void run() {
                if (needsFlash) {
                    try {
                        CaptureRequest.Builder t = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        t.addTarget(previewSurface);
                        applyCommon(t, true);
                        t.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                        session.capture(t.build(), null, bg);
                        bg.postDelayed(new Runnable() {
                            public void run() {
                                doCapture(mode, deviceDegrees, dng, cb);
                            }
                        }, 750);
                        return;
                    } catch (Exception ignored) {
                    }
                }
                doCapture(mode, deviceDegrees, dng, cb);
            }
        });
    }

    private void doCapture(int mode, int deviceDegrees, File dng, PhotoCb cb) {
        try {
            if (device == null || session == null || jpegReader == null) {
                cb.onFail("Camera not ready");
                return;
            }
            int n = mode == M_SINGLE ? 1 : (mode == M_HDR ? 3 : (mode == M_NIGHT ? 6 : 8));
            int rotation = jpegRotation(deviceDegrees);
            synchronized (lock) {
                frames.clear();
                expected = n;
                photoCb = cb;
                pendingRotation = rotation;
                pendingExif = exifFrom(lastResult);
                dngTarget = dng;
                if (pendingRaw != null) {
                    pendingRaw.close();
                    pendingRaw = null;
                }
                pendingRawResult = null;
            }

            CaptureResult base = lastResult;
            List<CaptureRequest> reqs = new ArrayList<CaptureRequest>();
            for (int i = 0; i < n; i++) {
                CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                b.addTarget(jpegReader.getSurface());
                if (rawReader != null && mode == M_SINGLE) b.addTarget(rawReader.getSurface());
                applyCommon(b, true);
                b.set(CaptureRequest.JPEG_QUALITY, (byte) 96);
                b.set(CaptureRequest.JPEG_ORIENTATION, mode == M_BURST ? rotation : 0);

                if (mode == M_HDR) {
                    float ev = i == 0 ? -2f : (i == 1 ? 0f : 2f);
                    if (s.manualExp && manualSensor) {
                        long e = (long) (s.expNs * Math.pow(2.0, ev));
                        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME,
                                Math.max(expRange.getLower(), Math.min(expRange.getUpper(), e)));
                    } else if (evRange != null) {
                        int idx = s.evIndex + Math.round(ev / evStep);
                        idx = Math.max(evRange.getLower(), Math.min(evRange.getUpper(), idx));
                        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, idx);
                        b.set(CaptureRequest.CONTROL_AE_LOCK, false);
                    }
                } else if (mode == M_NIGHT && manualSensor && base != null) {
                    Long e0 = base.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    Integer i0 = base.get(CaptureResult.SENSOR_SENSITIVITY);
                    if (e0 != null && i0 != null && e0 > 0) {
                        long t = Math.max(e0, 120000000L);
                        t = Math.min(t, 350000000L);
                        t = Math.max(t, e0);
                        t = Math.min(t, expRange.getUpper());
                        double iso = (double) i0 * e0 / t;
                        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, t);
                        b.set(CaptureRequest.SENSOR_SENSITIVITY, clampInt((int) iso, isoRange));
                        b.set(CaptureRequest.SENSOR_FRAME_DURATION, t);
                        b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
                    }
                }
                reqs.add(b.build());
            }

            CameraCaptureSession.CaptureCallback ccb = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession cs, CaptureRequest rq, TotalCaptureResult result) {
                    synchronized (lock) {
                        pendingRawResult = result;
                    }
                    tryWriteDng();
                }

                @Override
                public void onCaptureSequenceCompleted(CameraCaptureSession cs, int id, long frame) {
                    startRepeating();
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession cs, CaptureRequest rq, android.hardware.camera2.CaptureFailure f) {
                    PhotoCb c;
                    synchronized (lock) {
                        c = photoCb;
                        photoCb = null;
                    }
                    startRepeating();
                    if (c != null) postFail(c, "Capture failed");
                }
            };
            session.stopRepeating();
            if (n == 1) session.capture(reqs.get(0), ccb, bg);
            else session.captureBurst(reqs, ccb, bg);
        } catch (Exception e) {
            cb.onFail("Capture failed: " + e.getMessage());
            startRepeating();
        }
    }

    private void postFail(final PhotoCb c, final String m) {
        main.post(new Runnable() {
            public void run() {
                c.onFail(m);
            }
        });
    }

    private void onJpeg(ImageReader r) {
        Image im = null;
        try {
            im = r.acquireNextImage();
            if (im == null) return;
            ByteBuffer buf = im.getPlanes()[0].getBuffer();
            byte[] d = new byte[buf.remaining()];
            buf.get(d);
            deliverIfComplete(d);
        } catch (Throwable t) {
            // ignore a bad frame
        } finally {
            if (im != null) im.close();
        }
    }

    private void deliverIfComplete(byte[] d) {
        final List<byte[]> done;
        final PhotoCb cb;
        final MediaSaver.ExifInfo exif;
        final int rot;
        synchronized (lock) {
            if (photoCb == null) return;
            frames.add(d);
            if (frames.size() < expected) return;
            done = new ArrayList<byte[]>(frames);
            frames.clear();
            cb = photoCb;
            photoCb = null;
            exif = pendingExif;
            rot = pendingRotation;
        }
        main.post(new Runnable() {
            public void run() {
                cb.onFrames(done, exif, rot);
            }
        });
    }

    private void onRawImage(ImageReader r) {
        try {
            Image im = r.acquireNextImage();
            if (im == null) return;
            synchronized (lock) {
                if (pendingRaw != null) pendingRaw.close();
                pendingRaw = im;
            }
            tryWriteDng();
        } catch (Throwable ignored) {
        }
    }

    private void tryWriteDng() {
        Image raw;
        TotalCaptureResult res;
        File target;
        final PhotoCb cb;
        synchronized (lock) {
            if (pendingRaw == null || pendingRawResult == null || dngTarget == null) return;
            raw = pendingRaw;
            res = pendingRawResult;
            target = dngTarget;
            cb = photoCb != null ? photoCb : null;
            pendingRaw = null;
            dngTarget = null;
        }
        FileOutputStream out = null;
        try {
            DngCreator dng = new DngCreator(chars, res);
            dng.setOrientation(exifOrientation(pendingRotation));
            out = new FileOutputStream(target);
            dng.writeImage(out, raw);
            dng.close();
            final File f = target;
            main.post(new Runnable() {
                public void run() {
                    if (rawListener != null) rawListener.onRaw(f);
                }
            });
        } catch (Throwable t) {
            // RAW is a bonus; the JPEG still saves
        } finally {
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {
            }
            raw.close();
        }
    }

    private PhotoCb rawListener;

    void setRawListener(PhotoCb cb) {
        rawListener = cb;
    }

    private MediaSaver.ExifInfo exifFrom(CaptureResult r) {
        MediaSaver.ExifInfo i = new MediaSaver.ExifInfo();
        i.make = Build.MANUFACTURER;
        i.model = Build.MODEL;
        if (r != null) {
            Integer iso = r.get(CaptureResult.SENSOR_SENSITIVITY);
            Long exp = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Float ap = r.get(CaptureResult.LENS_APERTURE);
            Float fl = r.get(CaptureResult.LENS_FOCAL_LENGTH);
            if (iso != null) i.iso = iso;
            if (exp != null) i.exposureNs = exp;
            if (ap != null) i.fNumber = ap;
            if (fl != null) i.focalMm = fl;
        }
        return i;
    }

    CaptureResult lastResult() {
        return lastResult;
    }

    // ------------------------------------------------------------------ video

    /**
     * Starts recording. timelapseSeconds > 0 records a time-lapse (one frame every N seconds, no audio).
     * slowMo = true uses the phone's high-speed mode (120/240 fps).
     */
    void startVideo(final File out, final int deviceDegrees, final boolean audio,
                    final double timelapseSeconds, final boolean slowMo) {
        if (bg == null) return;
        bg.post(new Runnable() {
            public void run() {
                try {
                    startVideoNow(out, deviceDegrees, audio, timelapseSeconds, slowMo);
                } catch (Throwable t) {
                    releaseRecorder();
                    recording = false;
                    createPreviewSession();
                    final String m = t.getMessage();
                    main.post(new Runnable() {
                        public void run() {
                            listener.onVideoDone(null, 1, "Could not start recording: " + m);
                        }
                    });
                }
            }
        });
    }

    private void startVideoNow(File out, int deviceDegrees, boolean audio, double tl, boolean slowMo) throws Exception {
        if (device == null || previewSurface == null) throw new IllegalStateException("camera not ready");
        closeSessionQuietly();
        closeReaders();
        videoFile = out;
        recordingHighSpeed = false;
        recordingSlowFactor = 1;
        int rot = jpegRotation(deviceDegrees);

        Size size;
        int fps = s.fps;
        Range<Integer> hsRange = null;
        if (slowMo) {
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size bestSize = null;
            int bestFps = 0;
            if (highSpeedSupported && map != null) {
                for (Size z : map.getHighSpeedVideoSizes()) {
                    if ((long) z.getWidth() * z.getHeight() > 1920L * 1080L) continue;
                    for (Range<Integer> r : map.getHighSpeedVideoFpsRangesFor(z)) {
                        if (r.getLower().equals(r.getUpper()) && r.getUpper() >= 120) {
                            long area = (long) z.getWidth() * z.getHeight();
                            if (r.getUpper() > bestFps || (r.getUpper() == bestFps && bestSize != null
                                    && area > (long) bestSize.getWidth() * bestSize.getHeight())) {
                                bestFps = r.getUpper();
                                bestSize = z;
                                hsRange = r;
                            }
                        }
                    }
                }
            }
            if (bestSize == null) throw new IllegalStateException("this phone's camera has no 120/240 fps slow-motion mode");
            size = bestSize;
            fps = bestFps;
            recordingHighSpeed = true;
            recordingSlowFactor = Math.max(2, fps / 30);
        } else {
            size = videoSizeFor(s.videoQuality);
            if (size == null) throw new IllegalStateException("no video size available");
        }

        // build the recorder, falling back step by step if the phone refuses a setting
        boolean[][] attempts = tl > 0 || slowMo
                ? new boolean[][]{{false, false}}
                : new boolean[][]{{s.hevc, true}, {false, true}, {false, false}};
        Exception last = null;
        for (boolean[] a : attempts) {
            try {
                releaseRecorder();
                recorder = buildRecorder(out, size, fps, audio && !slowMo && tl <= 0, a[0], a[1], rot, tl);
                last = null;
                break;
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) throw last;

        final int pw, ph;
        Size ps = choosePreviewSize((double) size.getWidth() / size.getHeight(),
                recordingHighSpeed ? (long) size.getWidth() * size.getHeight() : 1920L * 1080L);
        if (recordingHighSpeed) ps = size;
        pw = ps.getWidth();
        ph = ps.getHeight();
        SurfaceTexture st = tv.getSurfaceTexture();
        st.setDefaultBufferSize(pw, ph);
        if (previewSurface != null) previewSurface.release();
        previewSurface = new Surface(st);

        List<Surface> surfaces = new ArrayList<Surface>();
        surfaces.add(previewSurface);
        surfaces.add(recorder.getSurface());
        final Range<Integer> hs = hsRange;
        CameraCaptureSession.StateCallback cb = new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession cs) {
                session = cs;
                try {
                    CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    b.addTarget(previewSurface);
                    b.addTarget(recorder.getSurface());
                    if (recordingHighSpeed) {
                        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, hs);
                        CameraConstrainedHighSpeedCaptureSession hsSession = (CameraConstrainedHighSpeedCaptureSession) cs;
                        List<CaptureRequest> list = hsSession.createHighSpeedRequestList(b.build());
                        hsSession.setRepeatingBurst(list, null, bg);
                    } else {
                        applyCommon(b, false);
                        cs.setRepeatingRequest(b.build(), null, bg);
                    }
                    recorder.start();
                    recording = true;
                    main.post(new Runnable() {
                        public void run() {
                            listener.onSession(true, pw, ph);
                        }
                    });
                } catch (Throwable t) {
                    abortRecording("Recording failed: " + t.getMessage());
                }
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession cs) {
                abortRecording("This video mode is not supported by the camera");
            }
        };
        if (recordingHighSpeed) device.createConstrainedHighSpeedCaptureSession(surfaces, cb, bg);
        else device.createCaptureSession(surfaces, cb, bg);
    }

    private MediaRecorder buildRecorder(File out, Size size, int fps, boolean audio, boolean hevc,
                                        boolean stereo, int rotation, double tlSeconds) throws Exception {
        MediaRecorder r = new MediaRecorder();
        try {
            if (audio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setOutputFile(out.getAbsolutePath());
            if (tlSeconds > 0) r.setCaptureRate(1.0 / tlSeconds);
            int pixels = size.getWidth() * size.getHeight();
            double bits = pixels >= 3840 * 2000 ? 48e6 : (pixels >= 1900 * 1000 ? 18e6 : 9e6);
            bits *= fps / 30.0;
            if (recordingHighSpeed) bits = Math.min(60e6, pixels * 8.0);
            if (s.tone == T_FLAT) bits *= 1.3;
            if (hevc) bits *= 0.65;
            r.setVideoEncodingBitRate((int) Math.min(bits, 100e6));
            r.setVideoFrameRate(tlSeconds > 0 ? 30 : fps);
            r.setVideoSize(size.getWidth(), size.getHeight());
            r.setVideoEncoder(hevc ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
            if (audio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                r.setAudioEncodingBitRate(192000);
                r.setAudioSamplingRate(44100);
                r.setAudioChannels(stereo ? 2 : 1);
            }
            r.setOrientationHint(rotation);
            r.prepare();
            return r;
        } catch (Exception e) {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
            throw e;
        }
    }

    private void abortRecording(String msg) {
        releaseRecorder();
        recording = false;
        if (videoFile != null) videoFile.delete();
        createPreviewSession();
        final String m = msg;
        main.post(new Runnable() {
            public void run() {
                listener.onVideoDone(null, 1, m);
            }
        });
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try {
                recorder.reset();
                recorder.release();
            } catch (Throwable ignored) {
            }
            recorder = null;
        }
    }

    void stopVideo() {
        if (bg == null) return;
        bg.post(new Runnable() {
            public void run() {
                stopVideoInternal(true);
            }
        });
    }

    private void stopVideoInternal(boolean notify) {
        String err = null;
        File f = videoFile;
        int slow = recordingSlowFactor;
        try {
            if (session != null) {
                session.stopRepeating();
                session.close();
                session = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (recorder != null) recorder.stop();
        } catch (Throwable t) {
            err = "Clip too short to save";
            if (f != null) f.delete();
            f = null;
        }
        releaseRecorder();
        recording = false;
        recordingHighSpeed = false;
        if (notify) {
            createPreviewSession();
            final File ff = f;
            final String ee = err;
            final int ss = slow;
            main.post(new Runnable() {
                public void run() {
                    listener.onVideoDone(ff, ss, ee);
                }
            });
        }
    }

    void pauseVideo(boolean pause) {
        try {
            if (recorder != null && recording && Build.VERSION.SDK_INT >= 24) {
                if (pause) recorder.pause();
                else recorder.resume();
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    private static int clampInt(int v, Range<Integer> r) {
        if (r == null) return v;
        return Math.max(r.getLower(), Math.min(r.getUpper(), v));
    }
}
