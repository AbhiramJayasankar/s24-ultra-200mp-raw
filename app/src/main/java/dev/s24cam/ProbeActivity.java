package dev.s24cam;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.s24cam.dng.JxlSelfTest;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Probe driven by adb intent extras, e.g.
 *   am start -n com.samsung.android.ruler/dev.s24cam.ProbeActivity --es mode capture
 *     --es cam 56 --ei w 8160 --ei h 6120 --ei sm 40
 * Logs to logcat tag S24CAM and saves frames into getExternalFilesDir(null).
 */
public class ProbeActivity extends Activity {
    private static final String TAG = SamsungCam.TAG;
    private TextView logView;

    private void p(String s) {
        Log.i(TAG, s);
        runOnUiThread(() -> logView.append(s + "\n"));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        logView = new TextView(this);
        logView.setTextSize(11);
        ScrollView sv = new ScrollView(this);
        sv.addView(logView);
        setContentView(sv);
        if (getIntent().getBooleanExtra("jxltest", false) || getIntent().getBooleanExtra("compact", false)
                || getIntent().getBooleanExtra("lumdirbench", false)) {
            start(getIntent());
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
            p("requested CAMERA permission; relaunch after granting");
            return;
        }
        start(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        start(intent);
    }

    private void start(Intent in) {
        if (in.getBooleanExtra("lumdirbench", false)) {
            new Thread(() -> {
                try {
                    LumdirBenchmark.run(getExternalFilesDir(null),
                            str(in, "input", "full_16320x12240_rs20400.raw10"), p -> p(p));
                } catch (Throwable t) {
                    p("LUMDIRBENCH FAILED " + Log.getStackTraceString(t));
                } finally {
                    runOnUiThread(this::finish);
                }
            }, "lumdirbench").start();
            return;
        }
        if (in.getBooleanExtra("compact", false)) {
            new Thread(() -> {
                boolean retry=CompactRunner.run(getApplicationContext(),new java.util.concurrent.atomic.AtomicBoolean());
                if(retry) CompactJobService.schedule(getApplicationContext());
                p("Compact: probe finished"+(retry?"; retry scheduled while charging":""));
                runOnUiThread(this::finish);
            }, "compact probe").start();
            return;
        }
        if (in.getBooleanExtra("jxltest", false)) {
            new Thread(() -> {
                try {
                    String summary = JxlSelfTest.run(getExternalFilesDir("jxltest"));
                    for (String line : summary.split("\n")) p(line);
                } catch (Throwable t) {
                    p("JXL SELF TEST FAILED " + Log.getStackTraceString(t));
                } finally {
                    runOnUiThread(this::finish);
                }
            }, "jxltest").start();
            return;
        }
        String mode = in.getStringExtra("mode");
        if (mode == null) mode = "list";
        final String m = mode;
        new Thread(() -> {
            try {
                if (m.equals("list")) list();
                else capture(in);
            } catch (Throwable t) {
                p("FATAL " + Log.getStackTraceString(t));
            }
            p("=== PROBE DONE ===");
        }, "probe").start();
    }

    private void list() throws Exception {
        CameraManager cm = getSystemService(CameraManager.class);
        p("package=" + getPackageName() + " idList=" + Arrays.toString(cm.getCameraIdList()));
        for (String id : new String[]{"0", "1", "2", "3", "20", "21", "23", "24", "52", "54", "56", "58"}) {
            try {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                p("cam " + id + " OK facing=" + c.get(CameraCharacteristics.LENS_FACING)
                        + " focal=" + Arrays.toString(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS))
                        + " pixelArray=" + c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                        + " caps=" + Arrays.toString(caps));
                if (id.equals("20") || id.equals("56")) {
                    StringBuilder sk = new StringBuilder();
                    for (CaptureRequest.Key<?> k : c.getAvailableSessionKeys()) sk.append(k.getName()).append(' ');
                    p("   sessionKeys: " + sk);
                }
                int[] hr = SamsungCam.getVendor(c, "samsung.android.scaler.availableHighresRawStreamConfigurations", int[].class);
                if (hr != null) p("   highresRaw=" + Arrays.toString(hr));
                int[] pic = SamsungCam.getVendor(c, "samsung.android.scaler.availablePictureStreamConfigurations", int[].class);
                if (pic != null && pic.length >= 8) p("   picture[0..7]=" + Arrays.toString(Arrays.copyOf(pic, 8)));
            } catch (Throwable t) {
                p("cam " + id + " ERR " + t);
            }
        }
    }

    /** One output stream parsed from "fmt:WxH:opt[:m]" (m = maximum-resolution pixel mode). */
    private static final class Stream {
        final String fmtName;
        final int format, w, h, option;
        /** "m" = maximum-resolution pixel mode, "c" = CROPPED_RAW stream use case (in-sensor crop). */
        final boolean maxRes, cropped;
        ImageReader reader;
        final CountDownLatch got = new CountDownLatch(1);

        Stream(String spec) {
            String[] f = spec.split(":");
            fmtName = f[0];
            switch (fmtName) {
                case "yuv": format = ImageFormat.YUV_420_888; break;
                case "raw10": format = ImageFormat.RAW10; break;
                case "raw16": format = ImageFormat.RAW_SENSOR; break;
                case "jpeg": format = ImageFormat.JPEG; break;
                default: throw new IllegalArgumentException("format " + fmtName);
            }
            String[] wh = f[1].split("x");
            w = Integer.parseInt(wh[0]);
            h = Integer.parseInt(wh[1]);
            option = Integer.decode(f[2]);
            maxRes = f.length > 3 && f[3].contains("m");
            cropped = f.length > 3 && f[3].contains("c");
        }

        boolean isPreview() { return option == SamsungCam.OPT_PREVIEW; }
        boolean isSaved() { return format != ImageFormat.YUV_420_888 || w >= 4000; }
        @Override public String toString() {
            return fmtName + " " + w + "x" + h + " opt=0x" + Integer.toHexString(option) + (maxRes ? " MAXRES" : "");
        }
    }

    static final String DEFAULT_STREAMS =
            "yuv:1440x1080:1,jpeg:5712x4284:2,yuv:5712x4284:2,raw10:8160x6120:2,yuv:512x384:4";

    private void capture(Intent in) throws Exception {
        final String cam = str(in, "cam", "56");
        final String spec = str(in, "streams", DEFAULT_STREAMS);
        final int sm = in.getIntExtra("sm", SamsungCam.SM_EXPERT_RAW_HIGHRES);
        final int ssm = in.getIntExtra("ssm", 0);
        final boolean reqMaxRes = in.getBooleanExtra("maxres", false);
        final int adaptive = in.getIntExtra("adaptive", -1);
        final int physId = in.getIntExtra("physid", -1);
        final boolean setParams = in.getBooleanExtra("params", true);
        final boolean save = in.getBooleanExtra("save", true);
        final int settle = in.getIntExtra("settle", 10);
        // Formats the still request targets (default: every non-preview stream).
        final String stillTargets = str(in, "still", "");
        final int orientation = in.getIntExtra("orient", -1);
        // Extra vendor keys for every request, "name=value;name=value" (names may omit "samsung.android.").
        final String extraKeys = str(in, "keys", "");
        // crop=x,y,w,h in frame pixels for the frame size of each saved RAW stream (x multiple of 4).
        final int[] crop = parseCrop(str(in, "crop", ""));
        final float zoom = in.getFloatExtra("zoom", -1f);
        final List<Stream> streams = new ArrayList<>();
        for (String s : spec.split(",")) streams.add(new Stream(s.trim()));
        final String tag = "cam" + cam + "_sm" + sm;
        p("=== capture " + tag + " streams=" + streams + " reqMaxRes=" + reqMaxRes + " adaptive=" + adaptive
                + " physid=" + physId + " params=" + setParams + " ssm=" + ssm);

        CameraManager cm = getSystemService(CameraManager.class);
        HandlerThread ht = new HandlerThread("cam");
        ht.start();
        Handler hd = new Handler(ht.getLooper());
        CountDownLatch opened = new CountDownLatch(1);
        CameraDevice[] dev = new CameraDevice[1];
        cm.openCamera(cam, new CameraDevice.StateCallback() {
            public void onOpened(CameraDevice d) { dev[0] = d; opened.countDown(); }
            public void onDisconnected(CameraDevice d) { p("device disconnected"); opened.countDown(); }
            public void onError(CameraDevice d, int e) { p("device onError " + e); opened.countDown(); }
        }, hd);
        if (!opened.await(8, TimeUnit.SECONDS) || dev[0] == null) {
            p("RESULT open failed");
            ht.quitSafely();
            return;
        }
        CameraDevice d = dev[0];
        CameraCharacteristics chars = cm.getCameraCharacteristics(cam);
        p("opened cam " + cam);

        try {
            if (setParams) {
                String ps = str(in, "pre", "") + SamsungCam.params(sm, ssm) + str(in, "post", "");
                p("params: " + ps);
                p("setParameters via " + SamsungCam.setParameters(d, ps));
            }

            final long ts = System.currentTimeMillis();
            final File dir = getExternalFilesDir(null);
            List<OutputConfiguration> outs = new ArrayList<>();
            Stream preview = null;
            for (Stream st : streams) {
                st.reader = ImageReader.newInstance(st.w, st.h, st.format,
                        st.isPreview() ? 4 : (long) st.w * st.h > 60_000_000L ? 1 : 2);
                if (st.isPreview() && preview == null) {
                    preview = st;
                    st.reader.setOnImageAvailableListener(r -> {
                        Image i = r.acquireLatestImage();
                        if (i != null) i.close();
                    }, hd);
                } else {
                    st.reader.setOnImageAvailableListener(r -> {
                        Image i = r.acquireNextImage();
                        if (i == null) return;
                        Image.Plane pl = i.getPlanes()[0];
                        p("*** " + st.fmtName + " " + i.getWidth() + "x" + i.getHeight() + " rowStride=" + pl.getRowStride()
                                + " pixelStride=" + pl.getPixelStride() + " bytes=" + pl.getBuffer().remaining());
                        if (save && crop == null && st.format == ImageFormat.YUV_420_888 && st.w >= 4000) {
                            Image.Plane vu = i.getPlanes()[2];
                            writeBuffer(new File(dir, tag + "_" + ts + "_" + i.getWidth() + "x" + i.getHeight()
                                    + "_rs" + vu.getRowStride() + "_ps" + vu.getPixelStride() + ".vu"), vu.getBuffer());
                        }
                        if (save && crop != null && (st.format == ImageFormat.RAW10 || st.format == ImageFormat.RAW_SENSOR)) {
                            writeCrop(new File(dir, tag + "_" + ts + "_crop" + crop[0] + "_" + crop[1] + "_"
                                    + crop[2] + "x" + crop[3] + "_rs" + crop[2] * bytesPerPixel(st.format)
                                    / (st.format == ImageFormat.RAW10 ? 4 : 1)
                                    + (st.format == ImageFormat.RAW10 ? ".raw10" : ".raw16")), pl, st.format, crop);
                        } else if (save && crop == null && st.isSaved()) {
                            String ext = st.format == ImageFormat.JPEG ? ".jpg"
                                    : st.format == ImageFormat.YUV_420_888 ? ".y8" : "." + st.fmtName;
                            writeBuffer(new File(dir, tag + "_" + ts + "_" + i.getWidth() + "x" + i.getHeight()
                                    + "_rs" + pl.getRowStride() + ext), pl.getBuffer());
                        }
                        i.close();
                        st.got.countDown();
                    }, hd);
                }
                OutputConfiguration oc = SamsungCam.output(st.reader.getSurface(), st.option);
                if (st.cropped) oc.setStreamUseCase(CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_CROPPED_RAW);
                if (st.maxRes) oc.addSensorPixelModeUsed(CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
                outs.add(oc);
            }
            if (preview == null) throw new IllegalArgumentException("need a preview stream (opt 1)");

            CaptureRequest.Builder pb = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            pb.addTarget(preview.reader.getSurface());
            if (zoom > 0) pb.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
            applyVendor(pb, sm, ssm, adaptive, physId);
            applyExtra(pb, extraKeys, true);
            final boolean torch = in.getBooleanExtra("torch", false);
            if (torch) pb.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            // focus = diopters for a fixed manual focus (same lens in every mode); af = run
            // autofocus once on the centre of the frame and report the distance it chose.
            final float focus = in.getFloatExtra("focus", -1f);
            final boolean af = in.getBooleanExtra("af", false);
            if (focus >= 0) {
                pb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                pb.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus);
            }
            CountDownLatch cfg = new CountDownLatch(1);
            CameraCaptureSession[] sess = new CameraCaptureSession[1];
            SessionConfiguration sc = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs,
                    hd::post, new CameraCaptureSession.StateCallback() {
                public void onConfigured(CameraCaptureSession s) { sess[0] = s; cfg.countDown(); }
                public void onConfigureFailed(CameraCaptureSession s) { p("SESSION CONFIG FAILED"); cfg.countDown(); }
            });
            sc.setSessionParameters(pb.build());
            d.createCaptureSession(sc);
            if (!cfg.await(15, TimeUnit.SECONDS) || sess[0] == null) {
                p("RESULT session failed");
                return;
            }
            p("session configured");
            AtomicInteger previews = new AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<TotalCaptureResult> lastPrev = new java.util.concurrent.atomic.AtomicReference<>();
            CameraCaptureSession.CaptureCallback prevCb = new CameraCaptureSession.CaptureCallback() {
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                    previews.incrementAndGet();
                    lastPrev.set(res);
                }
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure f) {
                    p("preview failed reason=" + f.getReason());
                }
            };
            sess[0].setRepeatingRequest(pb.build(), prevCb, hd);
            long t0 = System.currentTimeMillis();
            while (previews.get() < settle && System.currentTimeMillis() - t0 < 10000) Thread.sleep(30);
            p("previews before still: " + previews.get());
            if (af) {
                android.graphics.Rect a = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                int rw = a.width() / 10, rh = a.height() / 10;
                android.hardware.camera2.params.MeteringRectangle[] centre = {
                        new android.hardware.camera2.params.MeteringRectangle(a.centerX() - rw / 2, a.centerY() - rh / 2,
                                rw, rh, android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX - 1)};
                pb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                pb.set(CaptureRequest.CONTROL_AF_REGIONS, centre);
                sess[0].setRepeatingRequest(pb.build(), prevCb, hd);
                Thread.sleep(300);
                pb.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                sess[0].capture(pb.build(), prevCb, hd);
                pb.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                long ta = System.currentTimeMillis();
                Integer st = null;
                while (System.currentTimeMillis() - ta < 5000) {
                    Thread.sleep(50);
                    TotalCaptureResult r = lastPrev.get();
                    st = r == null ? null : r.get(CaptureResult.CONTROL_AF_STATE);
                    if (st != null && (st == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                            || st == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) break;
                }
                Thread.sleep(200);
                p("AF state=" + st + " focusDistance=" + lastPrev.get().get(CaptureResult.LENS_FOCUS_DISTANCE)
                        + " diopters, minFocus=" + chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE));
            }

            CaptureRequest.Builder sb = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            for (Stream st : streams) {
                if (st == preview) continue;
                if (stillTargets.isEmpty() || stillTargets.contains(st.fmtName)) sb.addTarget(st.reader.getSurface());
                else st.got.countDown();
            }
            if (orientation >= 0) sb.set(CaptureRequest.JPEG_ORIENTATION, orientation);
            int iso = in.getIntExtra("iso", 0);
            long expUs = in.getIntExtra("expus", 0);
            if (iso > 0 && expUs > 0) {
                sb.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                sb.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
                sb.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expUs * 1000L);
            }
            if (reqMaxRes) sb.set(CaptureRequest.SENSOR_PIXEL_MODE, CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
            sb.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            if (zoom > 0) sb.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
            applyVendor(sb, sm, ssm, adaptive, physId);
            applyExtra(sb, extraKeys, false);
            applyExtra(sb, str(in, "skeys", ""), true);
            if (torch) sb.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            if (focus >= 0) {
                sb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                sb.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus);
            }
            TotalCaptureResult[] stillResult = new TotalCaptureResult[1];
            long tFire = System.currentTimeMillis();
            sess[0].capture(sb.build(), new CameraCaptureSession.CaptureCallback() {
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                    stillResult[0] = res;
                    p("still completed in " + (System.currentTimeMillis() - tFire) + " ms, pixelMode="
                            + res.get(CaptureResult.SENSOR_PIXEL_MODE) + " exp=" + res.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                            + " iso=" + res.get(CaptureResult.SENSOR_SENSITIVITY)
                            + " focus=" + res.get(CaptureResult.LENS_FOCUS_DISTANCE)
                            + " rawCrop=" + res.get(CaptureResult.SCALER_RAW_CROP_REGION)
                            + " crop=" + res.get(CaptureResult.SCALER_CROP_REGION));
                }
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure f) {
                    p("still FAILED reason=" + f.getReason());
                }
            }, hd);
            StringBuilder res = new StringBuilder();
            long deadline = System.currentTimeMillis() + 40000;
            for (Stream st : streams) {
                if (st == preview) continue;
                boolean ok = st.got.await(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                res.append(st.fmtName).append(' ').append(st.w).append('x').append(st.h).append('=').append(ok).append("; ");
            }
            p("RESULT " + res + "total " + (System.currentTimeMillis() - tFire) + " ms");
            if (save && stillResult[0] != null) {
                writeText(new File(dir, tag + "_" + ts + "_meta.txt"), Meta.describe(chars, stillResult[0]));
            }
            sess[0].stopRepeating();
            sess[0].close();
            for (Stream st : streams) st.reader.close();
        } finally {
            d.close();
            ht.quitSafely();
        }
    }

    private void applyExtra(CaptureRequest.Builder b, String spec, boolean log) {
        if (spec.isEmpty()) return;
        for (String kv : spec.split(";")) {
            String[] p = kv.split("=", 2);
            String name = p[0].startsWith("samsung.") || p[0].startsWith("android.") ? p[0] : "samsung.android." + p[0];
            String type = SamsungCam.setVendorAny(b, name, p[1]);
            if (log) p(name + "=" + p[1] + " -> " + (type == null ? "REJECTED" : type));
        }
    }

    private void applyVendor(CaptureRequest.Builder b, int sm, int ssm, int adaptive, int physId) {
        SamsungCam.setVendor(b, "samsung.android.control.shootingMode", Integer.class, sm);
        SamsungCam.setVendor(b, "samsung.android.control.ssmShotMode", Integer.class, ssm);
        if (adaptive >= 0) SamsungCam.setVendor(b, "samsung.android.control.adaptivePixelMode", Integer.class, adaptive);
        if (physId >= 0) SamsungCam.setVendor(b, "samsung.android.control.capturePhysicalId", Integer.class, physId);
    }

    private void writeBuffer(File f, ByteBuffer bb) {
        try (FileOutputStream fos = new FileOutputStream(f); FileChannel ch = fos.getChannel()) {
            long n = 0;
            while (bb.hasRemaining()) n += ch.write(bb);
            p("saved " + f.getName() + " (" + n + " bytes)");
        } catch (Throwable t) {
            p("save ERR " + t);
        }
    }

    private static int bytesPerPixel(int format) {
        return format == ImageFormat.RAW10 ? 5 : 2; // RAW10: 5 bytes per 4 pixels
    }

    private static int[] parseCrop(String s) {
        if (s.isEmpty()) return null;
        String[] f = s.split(",");
        int[] c = new int[4];
        for (int i = 0; i < 4; i++) c[i] = Integer.parseInt(f[i].trim());
        if (c[0] % 4 != 0 || c[2] % 4 != 0) throw new IllegalArgumentException("crop x and w must be multiples of 4");
        return c;
    }

    /** Saves rows y..y+h, columns x..x+w of a RAW10 or RAW16 plane, rows packed tightly. */
    private void writeCrop(File f, Image.Plane pl, int format, int[] c) {
        ByteBuffer src = pl.getBuffer();
        int rs = pl.getRowStride();
        int from = format == ImageFormat.RAW10 ? c[0] * 5 / 4 : c[0] * 2;
        int len = format == ImageFormat.RAW10 ? c[2] * 5 / 4 : c[2] * 2;
        byte[] out = new byte[len * c[3]];
        for (int y = 0; y < c[3]; y++) {
            ByteBuffer d = src.duplicate();
            d.position((c[1] + y) * rs + from);
            d.get(out, y * len, len);
        }
        writeBuffer(f, ByteBuffer.wrap(out));
    }

    private void writeText(File f, String s) {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(s.getBytes());
            p("saved " + f.getName());
        } catch (Throwable t) {
            p("meta save ERR " + t);
        }
    }

    private static String str(Intent in, String k, String def) {
        String v = in.getStringExtra(k);
        return v == null ? def : v;
    }
}
