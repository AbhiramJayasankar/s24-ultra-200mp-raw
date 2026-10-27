package dev.s24cam;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The camera screen: viewfinder, mode selector, shutter and save status. */
public class CameraActivity extends Activity implements HighResCamera.Listener {
    private static final String TAG = SamsungCam.TAG;

    private HighResCamera camera;
    private TextureView viewfinder;
    private Surface previewSurface;
    private TextView readout, status, torchButton;
    private Ui.ShutterView shutter;
    private ImageView lastShot;
    private final Ui.ModeTile[] tiles = new Ui.ModeTile[Mode.values().length];
    private final ExecutorService saver = Executors.newSingleThreadExecutor();

    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable meter = this::meter;
    private long exposureNs = 33_000_000L;
    private int iso = 400;
    /** Third stops offered by the dials; clamped to what the sensor reports. */
    private static final int[] ISO_STOPS = {50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640,
            800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800};
    private static final String[] SHUTTER_LABELS = {"1/8000", "1/6400", "1/5000", "1/4000", "1/3200",
            "1/2500", "1/2000", "1/1600", "1/1250", "1/1000", "1/800", "1/640", "1/500", "1/400", "1/320",
            "1/250", "1/200", "1/160", "1/125", "1/100", "1/80", "1/60", "1/50", "1/40", "1/30", "1/25",
            "1/20", "1/15", "1/13", "1/10", "1/8", "1/6", "1/5", "1/4", "0.3", "0.4", "0.5", "0.6", "0.8", "1"};
    private boolean manualIso, manualShutter;
    private int isoStop, shutterStop;
    private Ui.ExposureDial isoDial, shutterDial;
    private View exposureBar;

    private Mode mode = Mode.MAIN_200;
    private boolean torch, saving, resumed;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        CompactJobService.scheduleOnStart(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Ui.BLACK);
        getWindow().setNavigationBarColor(Ui.BLACK);
        camera = new HighResCamera(getSystemService(CameraManager.class), this);
        setContentView(buildLayout());
        select(mode, false);
    }

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BLACK);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(0, i.top, 0, i.bottom);
            return WindowInsets.CONSUMED;
        });
        int pad = (int) Ui.dp(this, 16);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(pad, 0, (int) Ui.dp(this, 4), 0);
        readout = text(14, Ui.SLATE);
        readout.setTypeface(Ui.FIGURES);
        top.addView(readout, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        torchButton = text(14, Ui.SLATE);
        torchButton.setPadding(pad, pad, pad, pad);
        torchButton.setFocusable(true);
        torchButton.setOnClickListener(v -> setTorch(!torch));
        top.addView(torchButton);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Ui.dp(this, 52)));

        FrameLayout frame = new FrameLayout(this) {
            @Override protected void onMeasure(int w, int h) {
                int width = MeasureSpec.getSize(w);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 4 / 3, MeasureSpec.EXACTLY));
            }
        };
        viewfinder = new TextureView(this);
        viewfinder.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                st.setDefaultBufferSize(1440, 1080);
                previewSurface = new Surface(st);
                openIfReady();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                camera.close();
                previewSurface = null;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        frame.addView(viewfinder);
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(0x99000000);
        isoDial = new Ui.ExposureDial(this, "ISO");
        isoDial.setListener(new Ui.ExposureDial.Listener() {
            @Override public void onStep(int steps) { stepIso(steps); }
            @Override public void onTap() { manualIso = !manualIso; if (manualIso) isoStop = nearestIso(iso); applyExposure(); }
        });
        shutterDial = new Ui.ExposureDial(this, "Shutter speed");
        shutterDial.setListener(new Ui.ExposureDial.Listener() {
            @Override public void onStep(int steps) { stepShutter(steps); }
            @Override public void onTap() { manualShutter = !manualShutter; if (manualShutter) shutterStop = nearestShutter(exposureNs); applyExposure(); }
        });
        int dialH = (int) Ui.dp(this, 60);
        bar.addView(isoDial, new LinearLayout.LayoutParams(0, dialH, 1));
        bar.addView(shutterDial, new LinearLayout.LayoutParams(0, dialH, 1));
        exposureBar = bar;
        frame.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        root.addView(frame);

        LinearLayout modes = new LinearLayout(this);
        modes.setGravity(Gravity.CENTER);
        modes.setPadding(pad, (int) Ui.dp(this, 12), pad, 0);
        for (Mode m : Mode.values()) {
            Ui.ModeTile t = new Ui.ModeTile(this, m);
            t.setOnClickListener(v -> select(m, true));
            tiles[m.ordinal()] = t;
            modes.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        root.addView(modes);

        FrameLayout controls = new FrameLayout(this);
        int s = (int) Ui.dp(this, 76);
        shutter = new Ui.ShutterView(this);
        shutter.setOnClickListener(v -> takePhoto());
        controls.addView(shutter, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        lastShot = new ImageView(this);
        lastShot.setScaleType(ImageView.ScaleType.CENTER_CROP);
        lastShot.setClipToOutline(true);
        lastShot.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dp(CameraActivity.this, 6));
            }
        });
        lastShot.setContentDescription("Last photo");
        int t = (int) Ui.dp(this, 48);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(t, t, Gravity.CENTER_VERTICAL | Gravity.START);
        lp.leftMargin = pad * 2;
        controls.addView(lastShot, lp);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        root.addView(controls, cp);

        status = text(14, Ui.SLATE);
        status.setGravity(Gravity.CENTER);
        status.setPadding(pad, 0, pad, pad);
        status.setMinLines(2);
        root.addView(status);
        updateTorchLabel();
        showExposure();
        return root;
    }

    private TextView text(float sp, int color) {
        TextView v = new TextView(this);
        v.setTypeface(Ui.TEXT);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private void select(Mode m, boolean reopen) {
        if (saving) return;
        mode = m;
        for (Ui.ModeTile t : tiles) t.setSelected(t.mode == m);
        readout.setText(String.format(Locale.US, "%d × %d  RAW10%s", m.rawWidth, m.rawHeight,
                m.sixteenCell ? "  16-cell" : ""));
        setStatus("Opening the " + m.figure + "MP " + m.caption.toLowerCase(Locale.US) + " camera.", Ui.SLATE);
        exposureBar.setVisibility(m.sixteenCell ? View.VISIBLE : View.GONE);
        if (reopen) openIfReady();
    }

    private static double shutterSeconds(int stop) {
        String s = SHUTTER_LABELS[stop];
        return s.startsWith("1/") ? 1.0 / Integer.parseInt(s.substring(2)) : Double.parseDouble(s);
    }

    private int nearestIso(int value) {
        int best = 0;
        for (int i = 0; i < ISO_STOPS.length; i++)
            if (Math.abs(Math.log((double) ISO_STOPS[i] / value)) < Math.abs(Math.log((double) ISO_STOPS[best] / value))) best = i;
        return clampIsoStop(best);
    }

    private int nearestShutter(long ns) {
        int best = 0;
        for (int i = 0; i < SHUTTER_LABELS.length; i++)
            if (Math.abs(Math.log(shutterSeconds(i) * 1e9 / ns)) < Math.abs(Math.log(shutterSeconds(best) * 1e9 / ns))) best = i;
        return clampShutterStop(best);
    }

    private int clampIsoStop(int i) {
        android.util.Range<Integer> r = camera.isoRange();
        i = Math.max(0, Math.min(ISO_STOPS.length - 1, i));
        while (r != null && i > 0 && ISO_STOPS[i] > r.getUpper()) i--;
        while (r != null && i < ISO_STOPS.length - 1 && ISO_STOPS[i] < Math.max(50, r.getLower())) i++;
        return i;
    }

    private int clampShutterStop(int i) {
        android.util.Range<Long> r = camera.exposureRange();
        i = Math.max(0, Math.min(SHUTTER_LABELS.length - 1, i));
        while (r != null && i > 0 && shutterSeconds(i) * 1e9 > r.getUpper()) i--;
        while (r != null && i < SHUTTER_LABELS.length - 1 && shutterSeconds(i) * 1e9 < r.getLower()) i++;
        return i;
    }

    private void stepIso(int steps) {
        if (!manualIso) { manualIso = true; isoStop = nearestIso(iso); }
        isoStop = clampIsoStop(isoStop + steps);
        applyExposure();
    }

    private void stepShutter(int steps) {
        if (!manualShutter) { manualShutter = true; shutterStop = nearestShutter(exposureNs); }
        shutterStop = clampShutterStop(shutterStop + steps);
        applyExposure();
    }

    /** Puts manual values in force now; the meter fills in whichever setting is on auto. */
    private void applyExposure() {
        if (manualIso) iso = ISO_STOPS[isoStop];
        if (manualShutter) exposureNs = Math.round(shutterSeconds(shutterStop) * 1e9);
        camera.setManualExposure(exposureNs, iso);
        showExposure();
    }

    private void showExposure() {
        isoDial.show("ISO " + iso, manualIso ? "Manual" : "Auto", manualIso);
        String shutterText = manualShutter ? SHUTTER_LABELS[shutterStop]
                : exposureNs >= 300_000_000L ? String.format(Locale.US, "%.1f", exposureNs / 1e9)
                : "1/" + Math.round(1e9 / exposureNs);
        shutterDial.show(shutterText + " s", manualShutter ? "Manual" : "Auto", manualShutter);
    }

    private void setTorch(boolean on) {
        torch = on;
        camera.setTorch(on);
        updateTorchLabel();
    }

    private void updateTorchLabel() {
        torchButton.setText(torch ? "Torch on" : "Torch off");
        torchButton.setTextColor(torch ? Ui.GOLD : Ui.SLATE);
    }

    private void setStatus(String s, int color) {
        status.setText(s);
        status.setTextColor(color);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
            return;
        }
        openIfReady();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) openIfReady();
        else setStatus("Camera access is off. Allow it in Settings to take photos.", Ui.ERROR);
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(meter);
        // A save holds the RAW buffer, which dies with the camera session; close afterwards.
        if (!saving) camera.close();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        camera.release();
        saver.shutdown();
        super.onDestroy();
    }

    private void openIfReady() {
        if (!resumed || previewSurface == null || saving
                || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        camera.open(mode, previewSurface);
        if (torch) camera.setTorch(true);
    }

    private void takePhoto() {
        if (saving) return;
        long free = DngSaver.freeBytes();
        if (free < mode.expectedDngBytes() + 300L * 1024 * 1024) {
            setStatus(String.format(Locale.US, "Not enough space: a %sMP DNG needs about %d MB, %d MB free.",
                    mode.figure, mode.expectedDngBytes() >> 20, free >> 20), Ui.ERROR);
            return;
        }
        saving = true;
        shutter.setProgress(0);
        Bitmap frame = viewfinder.getBitmap(240, 320);
        if (frame != null) lastShot.setImageBitmap(frame);
        setStatus("Capturing " + mode.figure + "MP.", Ui.BONE);
        camera.capture();
    }

    /**
     * Simple auto exposure for the 200MP session: meter the viewfinder, aim for a mid-grey
     * average, and favour shutter speeds of 1/30 s or faster before raising ISO.
     */
    private void meter() {
        ui.removeCallbacks(meter);
        if (!mode.sixteenCell || !resumed) return;
        if (!saving) {
            Bitmap b = viewfinder.getBitmap(48, 64);
            if (b != null) {
                int[] px = new int[b.getWidth() * b.getHeight()];
                b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
                double sum = 0;
                for (int c : px) {
                    double y = (0.2126 * ((c >> 16) & 255) + 0.7152 * ((c >> 8) & 255) + 0.0722 * (c & 255)) / 255;
                    sum += Math.pow(Math.max(y, 1e-3), 2.2);
                }
                double linear = sum / px.length;
                double ratio = Math.sqrt(Math.min(8, Math.max(0.125, 0.18 / Math.max(linear, 1e-4))));
                if (Math.abs(ratio - 1) > 0.08 && !(manualIso && manualShutter)) {
                    android.util.Range<Integer> range = camera.isoRange();
                    int isoMin = Math.max(50, range == null ? 50 : range.getLower());
                    int isoMax = range == null ? 3200 : range.getUpper();
                    double total = exposureNs * (double) iso * ratio;
                    if (manualIso) {
                        // Shutter priority's mirror: ISO is fixed, the shutter follows the scene.
                        exposureNs = (long) Math.min(125_000_000L, Math.max(125_000L, total / iso));
                    } else if (manualShutter) {
                        iso = (int) Math.round(Math.min(isoMax, Math.max(isoMin, total / exposureNs)));
                    } else {
                        long exp = (long) Math.min(33_000_000L, Math.max(1_000_000L, total / 100));
                        double needIso = total / exp;
                        if (needIso > isoMax) exp = (long) Math.min(125_000_000L, total / isoMax);
                        iso = (int) Math.round(Math.min(isoMax, Math.max(isoMin, total / exp)));
                        exposureNs = exp;
                    }
                    camera.setManualExposure(exposureNs, iso);
                    showExposure();
                }
            }
        }
        ui.postDelayed(meter, 350);
    }

    @Override
    public void onPreviewStarted(Mode m) {
        runOnUiThread(() -> {
            if (m.sixteenCell) {
                camera.setManualExposure(exposureNs, iso);
                ui.postDelayed(meter, 500);
            }
            if (!saving) setStatus(m.sixteenCell
                    ? "Saves a 200MP DNG from the full 16-cell readout. Hold still, saving takes a while."
                    : "Saves a 50MP DNG from the full-resolution readout.", Ui.SLATE);
        });
    }

    @Override
    public void onError(String message) {
        Log.w(TAG, message);
        runOnUiThread(() -> {
            saving = false;
            shutter.setProgress(-1);
            setStatus(message, Ui.ERROR);
        });
    }

    @Override
    public void onRawCaptured(Mode m, Image raw, TotalCaptureResult result, CameraCharacteristics chars) {
        Long exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        String shot = String.format(Locale.US, "ISO %d, 1/%d s", iso == null ? 0 : iso,
                exp == null || exp == 0 ? 0 : Math.round(1e9 / exp));
        runOnUiThread(() -> setStatus("Writing DNG. " + shot, Ui.BONE));
        camera.pausePreview();
        long t0 = SystemClock.elapsedRealtime();
        saver.execute(() -> {
            String msg;
            int color;
            try {
                DngSaver.Saved s = DngSaver.save(getContentResolver(), m, raw, result, chars, false,
                        pct -> runOnUiThread(() -> {
                            shutter.setProgress(pct);
                            setStatus("Writing DNG " + pct + "%. " + shot, Ui.BONE);
                        }));
                CompactJobService.schedule(getApplicationContext());
                msg = String.format(Locale.US, "Saved %s, %d MB in %.0f s. %s.\nPictures/S24 HighRes",
                        s.name, s.bytes >> 20, (SystemClock.elapsedRealtime() - t0) / 1000.0, shot);
                color = Ui.BONE;
                Log.i(TAG, msg.replace('\n', ' '));
            } catch (Throwable e) {
                Log.e(TAG, "save failed", e);
                msg = "Could not save the DNG: " + e.getMessage();
                color = Ui.ERROR;
            } finally {
                raw.close();
            }
            final String fm = msg;
            final int fc = color;
            runOnUiThread(() -> {
                saving = false;
                shutter.setProgress(-1);
                setStatus(fm, fc);
                if (resumed) camera.resumePreview();
                else camera.close();
            });
        });
    }
}
