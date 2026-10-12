package com.procam.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.ExifInterface;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Writes photos / videos into DCIM/ProCam and tells the gallery about them. */
final class MediaSaver {

    private MediaSaver() {}

    interface ScanDone {
        void done(Uri uri);
    }

    static final class ExifInfo {
        String make, model;
        int iso;
        long exposureNs;
        float fNumber, focalMm;
        Double lat, lon;
    }

    static File dir() {
        File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "ProCam");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
    }

    static File newFile(String prefix, String ext) {
        return new File(dir(), prefix + "_" + stamp() + "." + ext);
    }

    static void saveJpeg(Bitmap b, File f, int quality) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            b.compress(Bitmap.CompressFormat.JPEG, quality, out);
            out.flush();
        } finally {
            out.close();
        }
    }

    static void writeBytes(byte[] data, File f) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
            out.flush();
        } finally {
            out.close();
        }
    }

    /** Writes capture info (device, ISO, shutter, aperture, focal length, GPS) into the JPEG. */
    static void writeExif(File f, ExifInfo i, int orientationTag) {
        try {
            ExifInterface e = new ExifInterface(f.getAbsolutePath());
            if (i.make != null) e.setAttribute(ExifInterface.TAG_MAKE, i.make);
            if (i.model != null) e.setAttribute(ExifInterface.TAG_MODEL, i.model);
            e.setAttribute(ExifInterface.TAG_DATETIME,
                    new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new Date()));
            e.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(orientationTag));
            if (i.iso > 0) e.setAttribute("ISOSpeedRatings", String.valueOf(i.iso));
            if (i.exposureNs > 0) {
                e.setAttribute("ExposureTime", String.format(Locale.US, "%.6f", i.exposureNs / 1e9));
            }
            if (i.fNumber > 0) e.setAttribute("FNumber", String.format(Locale.US, "%d/100", (int) (i.fNumber * 100)));
            if (i.focalMm > 0) e.setAttribute("FocalLength", String.format(Locale.US, "%d/100", (int) (i.focalMm * 100)));
            if (i.lat != null && i.lon != null) {
                e.setAttribute(ExifInterface.TAG_GPS_LATITUDE, dms(i.lat));
                e.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, i.lat >= 0 ? "N" : "S");
                e.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, dms(i.lon));
                e.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, i.lon >= 0 ? "E" : "W");
            }
            e.saveAttributes();
        } catch (Throwable ignored) {
        }
    }

    private static String dms(double v) {
        v = Math.abs(v);
        int d = (int) v;
        double mm = (v - d) * 60.0;
        int m = (int) mm;
        int s = (int) ((mm - m) * 60.0 * 1000.0);
        return d + "/1," + m + "/1," + s + "/1000";
    }

    static void scan(Context c, File f, final ScanDone cb) {
        MediaScannerConnection.scanFile(c.getApplicationContext(), new String[]{f.getAbsolutePath()}, null,
                new MediaScannerConnection.OnScanCompletedListener() {
                    @Override
                    public void onScanCompleted(String path, Uri uri) {
                        if (cb != null) cb.done(uri);
                    }
                });
    }

    /**
     * Turns a 120/240 fps clip into real slow motion by stretching its timestamps.
     * No re-encoding, so quality is untouched. Returns true on success.
     */
    static boolean slowMotion(File in, File out, int factor) {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = null;
        boolean started = false;
        try {
            ex.setDataSource(in.getAbsolutePath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0) return false;
            ex.selectTrack(track);

            int rotation = 0;
            try {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                r.setDataSource(in.getAbsolutePath());
                String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (rot != null) rotation = Integer.parseInt(rot);
                r.release();
            } catch (Throwable ignored) {
            }

            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outTrack = mux.addTrack(fmt);
            mux.setOrientationHint(rotation);
            mux.start();
            started = true;

            ByteBuffer buf = ByteBuffer.allocate(4 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                buf.clear();
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                info.set(0, size, ex.getSampleTime() * factor, ex.getSampleFlags());
                mux.writeSampleData(outTrack, buf, info);
                ex.advance();
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (mux != null) {
                    if (started) mux.stop();
                    mux.release();
                }
            } catch (Throwable ignored) {
            }
            ex.release();
        }
    }
}
