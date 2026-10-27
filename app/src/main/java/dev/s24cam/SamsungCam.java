package dev.s24cam;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.util.Log;
import android.view.Surface;

import java.lang.reflect.Method;

/**
 * Thin wrapper over the private One UI camera2 extensions that Samsung's own apps use
 * to reach the remosaic (50MP / 200MP) pipelines.
 *
 * Found on SM-S928B (One UI 9, Android 17) by reading logcat from the stock Camera and
 * Expert RAW apps:
 *  - CameraDevice.setParameters(String) sends a "key=value;" string to the UniHAL.
 *    Without samsungcamera=true the HAL treats the client as a plain third-party app.
 *  - OutputConfiguration.semCreateOutputConfiguration(group, surface, rotation, option)
 *    stores option << 16 in the stream use case. Option 1 = preview, 2 = picture,
 *    4 = thumbnail, 0x802 = 200MP JPEG in the stock app.
 */
final class SamsungCam {
    static final String TAG = "S24CAM";

    /** Hidden camera ids (need an allowlisted package name). */
    static final String CAM_LOGICAL_BACK = "20"; // stock Camera app, 200MP mode
    static final String CAM_TELE_3X = "52";
    static final String CAM_TELE_5X = "54";      // IMX854, 50MP
    static final String CAM_MAIN = "56";         // S5KHP2, 200MP (Expert RAW uses this)
    static final String CAM_ULTRAWIDE = "58";

    /** shootingmode values observed in logcat. */
    static final int SM_PHOTO = 1;
    static final int SM_HIGHRES_200MP = 39;     // stock Camera, 200M photo
    static final int SM_EXPERT_RAW_HIGHRES = 40; // Expert RAW, 50MP RAW

    static final int OPT_PREVIEW = 0x1;
    static final int OPT_PICTURE = 0x2;
    static final int OPT_THUMBNAIL = 0x4;

    private static Method bridgeSetParam;
    private static boolean bridgeLoaded;

    private SamsungCam() {}

    static String params(int shootingMode, int ssmShotMode) {
        return "first-entrance=false;samsungcamera=true;factorytest=false;shootingmode=" + shootingMode
                + ";recording-fps=0;sw-vdis=false;video-beautyface=false;vtmode=0;operation_mode=none;"
                + "ssm_shot_mode=" + ssmShotMode
                + ";recording_dr_mode=sdr;sw-super_vdis=false;stream_type=0;";
    }

    /**
     * CameraDevice.setParameters is on the hidden-API block list, so call it through
     * Samsung's own /system/framework/scamera_sdk_util.jar, whose code runs with platform
     * API access. Falls back to plain reflection.
     *
     * @return a short description of the route that worked, or null on failure
     */
    static String setParameters(CameraDevice device, String params) {
        synchronized (SamsungCam.class) {
            if (!bridgeLoaded) {
                bridgeLoaded = true;
                try {
                    ClassLoader cl = new dalvik.system.PathClassLoader(
                            "/system/framework/scamera_sdk_util.jar", SamsungCam.class.getClassLoader());
                    Class<?> helper = Class.forName(
                            "com.samsung.android.sdk.camera.impl.internal.CustomInterfaceHelper", true, cl);
                    bridgeSetParam = helper.getMethod("setSamsungParameter", CameraDevice.class, String.class);
                } catch (Throwable t) {
                    Log.w(TAG, "scamera_sdk_util bridge unavailable: " + t);
                }
            }
        }
        if (bridgeSetParam != null) {
            try {
                bridgeSetParam.invoke(null, device, params);
                return "bridge";
            } catch (Throwable t) {
                Log.w(TAG, "bridge setSamsungParameter failed: " + t);
            }
        }
        try {
            Method m = CameraDevice.class.getMethod("setParameters", String.class);
            m.invoke(device, params);
            return "reflection";
        } catch (Throwable t) {
            Log.w(TAG, "reflection setParameters failed: " + t);
        }
        return null;
    }

    /** Same as semCreateOutputConfiguration(-1, surface, 0, option). */
    static OutputConfiguration output(Surface surface, int option) {
        try {
            Method m = OutputConfiguration.class.getMethod("semCreateOutputConfiguration",
                    int.class, Surface.class, int.class, int.class);
            return (OutputConfiguration) m.invoke(null, -1, surface, 0, option);
        } catch (Throwable t) {
            Log.w(TAG, "semCreateOutputConfiguration failed, using setStreamUseCase: " + t);
            OutputConfiguration oc = new OutputConfiguration(surface);
            oc.setStreamUseCase(Integer.toUnsignedLong(option) << 16);
            return oc;
        }
    }

    /** Sets a Samsung vendor tag by name; returns false if the HAL does not know it. */
    static <T> boolean setVendor(CaptureRequest.Builder b, String name, Class<T> type, T value) {
        try {
            b.set(new CaptureRequest.Key<>(name, type), value);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "vendor key " + name + " rejected: " + t);
            return false;
        }
    }

    /**
     * Sets a vendor key whose type we do not know: tries Integer, Byte, Long, Float and
     * Boolean in turn and returns the type that was accepted (null if none).
     */
    static String setVendorAny(CaptureRequest.Builder b, String name, String value) {
        Object[][] tries;
        if (value.length() > 2 && value.charAt(1) == ':') {
            // explicit type: b:1 byte, i:1 int, l:1 long, f:1.0 float
            String v = value.substring(2);
            switch (value.charAt(0)) {
                case 'b': tries = new Object[][]{{Byte.class, Byte.parseByte(v)}}; break;
                case 'i': tries = new Object[][]{{Integer.class, Integer.parseInt(v)}}; break;
                case 'l': tries = new Object[][]{{Long.class, Long.parseLong(v)}}; break;
                case 'f': tries = new Object[][]{{Float.class, Float.parseFloat(v)}}; break;
                default: return null;
            }
        } else if (value.equals("true") || value.equals("false")) {
            boolean v = Boolean.parseBoolean(value);
            tries = new Object[][]{{Boolean.class, v}, {Byte.class, (byte) (v ? 1 : 0)}, {Integer.class, v ? 1 : 0}};
        } else if (value.contains(".")) {
            tries = new Object[][]{{Float.class, Float.parseFloat(value)}, {Double.class, Double.parseDouble(value)}};
        } else {
            long v = Long.parseLong(value);
            tries = new Object[][]{{Integer.class, (int) v}, {Byte.class, (byte) v}, {Long.class, v}, {Float.class, (float) v}};
        }
        for (Object[] t : tries) {
            try {
                @SuppressWarnings("unchecked")
                CaptureRequest.Key<Object> k = new CaptureRequest.Key<>(name, (Class<Object>) t[0]);
                b.set(k, t[1]);
                return ((Class<?>) t[0]).getSimpleName();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    static <T> T getVendor(CameraCharacteristics c, String name, Class<T> type) {
        try {
            return c.get(new CameraCharacteristics.Key<>(name, type));
        } catch (Throwable t) {
            return null;
        }
    }
}
