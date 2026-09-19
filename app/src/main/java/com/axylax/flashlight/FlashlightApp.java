package com.axylax.flashlight;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

public class FlashlightApp extends Application {

    private static final String TAG = "FlashlightApp";
    public static final String PREFS_NAME = "flashlight_state_prefs";
    public static final String KEY_TORCH_STATE = "is_torch_on";

    public static volatile Boolean currentHardwareTorchState = null;
    public static volatile String cachedTorchCameraId = null;

    private HandlerThread handlerThread;
    private CameraManager.TorchCallback torchCallback;

    @Override
    public void onCreate() {
        super.onCreate();
        initTorchTracker();
    }

    private void initTorchTracker() {
        try {
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cameraManager == null) return;

            cachedTorchCameraId = findTorchCameraId(cameraManager);

            handlerThread = new HandlerThread("TorchTrackerThread");
            handlerThread.start();
            Handler handler = new Handler(handlerThread.getLooper());

            torchCallback = new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String id, boolean enabled) {
                    if (cachedTorchCameraId == null || id.equals(cachedTorchCameraId)) {
                        currentHardwareTorchState = enabled;
                        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        prefs.edit().putBoolean(KEY_TORCH_STATE, enabled).apply();
                        Log.d(TAG, "Hardware torch state changed: id=" + id + ", enabled=" + enabled);
                    }
                }

                @Override
                public void onTorchModeUnavailable(String id) {
                    Log.d(TAG, "Torch unavailable: id=" + id);
                }
            };

            cameraManager.registerTorchCallback(torchCallback, handler);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize torch tracker", t);
        }
    }

    public static String findTorchCameraId(CameraManager cameraManager) {
        if (cameraManager == null) return null;
        try {
            String[] idList = cameraManager.getCameraIdList();
            // 1. First pass: Back-facing camera with flash unit
            for (String id : idList) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Boolean hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(hasFlash) && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
            }
            // 2. Second pass: Any camera with flash unit
            for (String id : idList) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Boolean hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(hasFlash)) {
                    return id;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error finding torch camera ID", t);
        }
        return null;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        try {
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cameraManager != null && torchCallback != null) {
                cameraManager.unregisterTorchCallback(torchCallback);
            }
            if (handlerThread != null) {
                handlerThread.quitSafely();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error terminating torch tracker", t);
        }
    }
}
