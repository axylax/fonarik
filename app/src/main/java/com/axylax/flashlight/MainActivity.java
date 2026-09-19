package com.axylax.flashlight;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        disableActivityTransitions();

        try {
            toggleFlashlight();
        } catch (Throwable t) {
            Log.e(TAG, "Error toggling flashlight", t);
        } finally {
            finish();
            disableActivityTransitions();
        }
    }

    private void disableActivityTransitions() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0);
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
        } else {
            overridePendingTransition(0, 0);
        }
    }

    private void toggleFlashlight() {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            Log.e(TAG, "CameraManager not available");
            return;
        }

        String cameraId = FlashlightApp.cachedTorchCameraId;
        if (cameraId == null) {
            cameraId = FlashlightApp.findTorchCameraId(cameraManager);
        }
        if (cameraId == null) {
            Log.e(TAG, "No camera with flash unit found");
            return;
        }

        SharedPreferences prefs = getSharedPreferences(FlashlightApp.PREFS_NAME, Context.MODE_PRIVATE);

        // 1. Determine current hardware torch state
        Boolean hardwareState = resolveCurrentTorchState(cameraManager, cameraId);
        boolean isCurrentlyOn = (hardwareState != null) ? hardwareState : prefs.getBoolean(FlashlightApp.KEY_TORCH_STATE, false);
        boolean targetState = !isCurrentlyOn;

        Log.d(TAG, "Toggling torch: currently=" + isCurrentlyOn + " -> target=" + targetState);

        boolean success = false;
        if (targetState) {
            // Turn ON: Samsung One UI max level (5) if supported, else standard on
            boolean turnedOnWithStrength = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    CameraCharacteristics chars = cameraManager.getCameraCharacteristics(cameraId);
                    Integer maxStrength = chars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
                    if (maxStrength != null && maxStrength > 1) {
                        cameraManager.turnOnTorchWithStrengthLevel(cameraId, maxStrength);
                        turnedOnWithStrength = true;
                        success = true;
                        Log.d(TAG, "Flashlight turned on with strength level: " + maxStrength);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "turnOnTorchWithStrengthLevel failed, falling back to setTorchMode", t);
                }
            }

            if (!turnedOnWithStrength) {
                try {
                    cameraManager.setTorchMode(cameraId, true);
                    success = true;
                    Log.d(TAG, "Flashlight turned on with default mode");
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to turn on flashlight via setTorchMode", t);
                }
            }
        } else {
            // Turn OFF
            try {
                cameraManager.setTorchMode(cameraId, false);
                success = true;
                Log.d(TAG, "Flashlight turned off");
            } catch (Throwable t) {
                Log.e(TAG, "Failed to turn off flashlight", t);
            }
        }

        if (success) {
            FlashlightApp.currentHardwareTorchState = targetState;
            prefs.edit().putBoolean(FlashlightApp.KEY_TORCH_STATE, targetState).apply();
            triggerHapticFeedback();
        }
    }

    private Boolean resolveCurrentTorchState(CameraManager cameraManager, final String targetCameraId) {
        // Fast path: cached by Application tracker
        Boolean state = FlashlightApp.currentHardwareTorchState;
        if (state != null) {
            return state;
        }

        // Cold start path: brief latch wait for initial callback
        final AtomicReference<Boolean> result = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        CameraManager.TorchCallback callback = new CameraManager.TorchCallback() {
            @Override
            public void onTorchModeChanged(String id, boolean enabled) {
                if (id.equals(targetCameraId)) {
                    result.set(enabled);
                    latch.countDown();
                }
            }

            @Override
            public void onTorchModeUnavailable(String id) {
                if (id.equals(targetCameraId)) {
                    latch.countDown();
                }
            }
        };

        try {
            cameraManager.registerTorchCallback(callback, null);
            latch.await(150, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            Log.w(TAG, "Latch torch callback wait failed", t);
        } finally {
            try {
                cameraManager.unregisterTorchCallback(callback);
                executor.shutdown();
            } catch (Throwable ignored) {
            }
        }

        return result.get();
    }

    private void triggerHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vibratorManager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vibratorManager != null) {
                    Vibrator vibrator = vibratorManager.getDefaultVibrator();
                    if (vibrator.hasVibrator()) {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
                        return;
                    }
                }
            }

            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(20);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Haptic feedback failed", t);
        }
    }
}
