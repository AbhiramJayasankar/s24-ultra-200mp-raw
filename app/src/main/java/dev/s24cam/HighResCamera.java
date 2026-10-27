package dev.s24cam;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs one Samsung high-resolution session: preview into a caller surface and single
 * RAW stills. All camera callbacks run on a private handler thread.
 */
final class HighResCamera {
    private static final String TAG = SamsungCam.TAG;

    interface Listener {
        void onPreviewStarted(Mode mode);
        void onError(String message);
        /** The caller owns {@code raw} and must close it. */
        void onRawCaptured(Mode mode, Image raw, TotalCaptureResult result, CameraCharacteristics chars);
    }

    private final CameraManager manager;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("camera");
    private final Handler handler;

    private Mode mode;
    private CameraDevice device;
    private CameraCaptureSession session;
    private CameraCharacteristics chars;
    private Surface previewSurface;
    private android.graphics.SurfaceTexture previewTexture;
    private int previewWidth, previewHeight;
    private final List<ImageReader> readers = new ArrayList<>();
    private ImageReader rawReader;
    private boolean torch;
    private volatile TotalCaptureResult lastPreview;
    /** Manual exposure used instead of the HAL's auto exposure (200MP mode); 0 = auto. */
    private volatile long manualExposureNs;
    private volatile int manualIso;
    private volatile boolean capturing;
    /** Streaming stopped while a 200MP save runs; see {@link #pausePreview}. */
    private boolean paused;

    HighResCamera(CameraManager manager, Listener listener) {
        this.manager = manager;
        this.listener = listener;
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    /**
     * @param texture the viewfinder's texture; its buffer size is set to width x height
     *     right before the stream is configured (see {@link #startSession}).
     */
    void open(Mode mode, Surface preview, android.graphics.SurfaceTexture texture, int width, int height) {
        handler.post(() -> {
            closeInternal();
            this.mode = mode;
            this.previewSurface = preview;
            this.previewTexture = texture;
            this.previewWidth = width;
            this.previewHeight = height;
            try {
                chars = manager.getCameraCharacteristics(mode.cameraId);
                manager.openCamera(mode.cameraId, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice d) {
                        device = d;
                        startSession();
                    }
                    @Override public void onDisconnected(CameraDevice d) {
                        d.close();
                        if (device == d) device = null;
                    }
                    @Override public void onError(CameraDevice d, int error) {
                        d.close();
                        if (device == d) device = null;
                        listener.onError("Camera stopped (error " + error + "). Pick the mode again to reopen it.");
                    }
                }, handler);
            } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
                listener.onError("Could not open camera " + mode.cameraId + ": " + e.getMessage());
            }
        });
    }

    private void startSession() {
        if (SamsungCam.setParameters(device, SamsungCam.params(mode.shootingMode, 0)) == null) {
            listener.onError("Samsung camera parameters were refused, so high-res modes are unavailable.");
            return;
        }
        // TextureView silently resets its buffer to the view size (portrait) whenever it
        // rebuilds its layer, e.g. after the app comes back. The stream takes whatever
        // size the buffer has now, so set it here, not when the view appears.
        if (previewTexture != null) previewTexture.setDefaultBufferSize(previewWidth, previewHeight);
        List<OutputConfiguration> outs = new ArrayList<>();
        outs.add(SamsungCam.output(previewSurface, SamsungCam.OPT_PREVIEW));
        rawReader = null;
        for (String spec : mode.streams.split(",")) {
            String[] f = spec.split(":");
            String[] wh = f[1].split("x");
            int w = Integer.parseInt(wh[0]), h = Integer.parseInt(wh[1]);
            int format = f[0].equals("jpeg") ? ImageFormat.JPEG : f[0].equals("raw10") ? ImageFormat.RAW10 : ImageFormat.YUV_420_888;
            ImageReader r = ImageReader.newInstance(w, h, format, 1);
            if (format == ImageFormat.RAW10) {
                rawReader = r;
                r.setOnImageAvailableListener(this::onRawAvailable, handler);
            } else {
                // Unused outputs the HAL insists on; drop their frames straight away.
                r.setOnImageAvailableListener(reader -> {
                    Image i = reader.acquireLatestImage();
                    if (i != null) i.close();
                }, handler);
            }
            readers.add(r);
            outs.add(SamsungCam.output(r.getSurface(), Integer.decode(f[2])));
        }
        try {
            CaptureRequest.Builder pb = previewRequest();
            SessionConfiguration sc = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs,
                    handler::post, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    repeatPreview();
                    listener.onPreviewStarted(mode);
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    listener.onError("This phone refused the " + mode.figure + "MP stream setup.");
                }
            });
            sc.setSessionParameters(pb.build());
            device.createCaptureSession(sc);
        } catch (CameraAccessException | RuntimeException e) {
            listener.onError("Could not start the " + mode.figure + "MP session: " + e.getMessage());
        }
    }

    private CaptureRequest.Builder previewRequest() throws CameraAccessException {
        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        b.addTarget(previewSurface);
        applyCommon(b);
        return b;
    }

    private void applyCommon(CaptureRequest.Builder b) {
        SamsungCam.setVendor(b, "samsung.android.control.shootingMode", Integer.class, mode.shootingMode);
        SamsungCam.setVendor(b, "samsung.android.control.ssmShotMode", Integer.class, 0);
        applyKeys(b, mode.keys);
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        b.set(CaptureRequest.FLASH_MODE, torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        if (manualExposureNs > 0) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExposureNs);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso);
        }
    }

    /**
     * The 200MP session leaves auto exposure parked at its start values, so the screen
     * runs its own metering and sets exposure here for preview and stills.
     */
    void setManualExposure(long exposureNs, int iso) {
        handler.post(() -> {
            manualExposureNs = exposureNs;
            manualIso = iso;
            if (session != null && !capturing && !paused) repeatPreview();
        });
    }

    /** Exposure time range of the open camera in ns, or null before it opens. */
    android.util.Range<Long> exposureRange() {
        CameraCharacteristics c = chars;
        return c == null ? null : c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
    }

    /** Sensitivity range of the open camera, or null before it opens. */
    android.util.Range<Integer> isoRange() {
        CameraCharacteristics c = chars;
        return c == null ? null : c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
    }

    private static void applyKeys(CaptureRequest.Builder b, String spec) {
        if (spec.isEmpty()) return;
        for (String kv : spec.split(";")) {
            String[] p = kv.split("=", 2);
            if (SamsungCam.setVendorAny(b, "samsung.android." + p[0], p[1]) == null)
                Log.w(TAG, "vendor key not accepted: " + p[0]);
        }
    }

    private void repeatPreview() {
        try {
            session.setRepeatingRequest(previewRequest().build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                    lastPreview = res;
                }
            }, handler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "preview restart failed", e);
        }
    }

    void setTorch(boolean on) {
        handler.post(() -> {
            torch = on;
            if (session != null && !paused) repeatPreview();
        });
    }

    /** Takes one RAW still; the result arrives in {@link Listener#onRawCaptured}. */
    void capture() {
        handler.post(() -> {
            if (session == null || capturing) return;
            capturing = true;
            try {
                CaptureRequest.Builder sb = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                for (ImageReader r : readers) {
                    String kind = r.getImageFormat() == ImageFormat.RAW10 ? "raw10"
                            : r.getImageFormat() == ImageFormat.JPEG ? "jpeg" : "yuv";
                    boolean big = r.getWidth() >= 4000;
                    if (big && mode.stillTargets.contains(kind)) sb.addTarget(r.getSurface());
                }
                applyCommon(sb);
                applyKeys(sb, mode.stillKeys);
                sb.set(CaptureRequest.JPEG_ORIENTATION, chars.get(CameraCharacteristics.SENSOR_ORIENTATION));
                session.capture(sb.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                        stillResult = res;
                        deliverIfReady();
                    }
                    @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure f) {
                        capturing = false;
                        listener.onError("The camera dropped that shot (reason " + f.getReason() + "). Try again.");
                    }
                }, handler);
            } catch (CameraAccessException | RuntimeException e) {
                capturing = false;
                listener.onError("Capture failed: " + e.getMessage());
            }
        });
    }

    private TotalCaptureResult stillResult;
    private Image stillImage;

    private void onRawAvailable(ImageReader r) {
        Image i = r.acquireNextImage();
        if (i == null) return;
        if (!capturing) { i.close(); return; }
        stillImage = i;
        deliverIfReady();
    }

    private void deliverIfReady() {
        if (stillImage == null || stillResult == null) return;
        Image img = stillImage;
        TotalCaptureResult res = stillResult;
        stillImage = null;
        stillResult = null;
        capturing = false;
        listener.onRawCaptured(mode, img, res, chars);
    }

    /**
     * Stops sensor streaming while a save runs. The session (and with it the RAW
     * image being saved) stays open; the viewfinder freezes on its last frame.
     * The save is power-limited: with the 200MP readout streaming it ran ~15%
     * slower on the S24 Ultra.
     */
    void pausePreview() {
        handler.post(() -> {
            if (session == null || paused) return;
            paused = true;
            try { session.stopRepeating(); }
            catch (CameraAccessException | IllegalStateException e) { Log.w(TAG, "preview pause failed", e); }
        });
    }

    void resumePreview() {
        handler.post(() -> {
            if (!paused) return;
            paused = false;
            if (session != null) repeatPreview();
        });
    }

    TotalCaptureResult lastPreviewResult() {
        return lastPreview;
    }

    void close() {
        handler.post(this::closeInternal);
    }

    void release() {
        handler.post(() -> {
            closeInternal();
            thread.quitSafely();
        });
    }

    private void closeInternal() {
        if (session != null) {
            try { session.close(); } catch (RuntimeException ignored) { }
            session = null;
        }
        if (device != null) {
            device.close();
            device = null;
        }
        if (stillImage != null) { stillImage.close(); stillImage = null; }
        stillResult = null;
        capturing = false;
        paused = false;
        for (ImageReader r : readers) r.close();
        readers.clear();
        rawReader = null;
        lastPreview = null;
        manualExposureNs = 0;
    }
}
