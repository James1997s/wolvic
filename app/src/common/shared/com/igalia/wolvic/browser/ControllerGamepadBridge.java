/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.browser;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Feeds OpenXR controller state into Gecko's Android GamepadPlatformService.
 *
 * GeckoView currently has no public virtual-gamepad API, so this uses the
 * AndroidGamepadManager JNI entry points. Keep the reflection isolated here:
 * these are Gecko internals and should be rechecked when updating GeckoView.
 */
public final class ControllerGamepadBridge {
    private static final String TAG = "WolvicGamepad";
    private static final String GECKO_MANAGER = "org.mozilla.gecko.AndroidGamepadManager";
    private static final int BUTTON_COUNT = 17;
    private static final int AXIS_COUNT = 4;
    private static final long UPDATE_DELAY_MS = 16;
    private static final long ADD_RETRY_DELAY_MS = 750;
    private static final Object LOCK = new Object();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static boolean sRequested;
    private static boolean sControllerAvailable;
    private static boolean sApplyQueued;
    private static boolean sReflectionAttempted;
    private static boolean sBridgeUnavailable;
    private static boolean sLoggedFailure;
    private static float[] sPendingButtons = new float[BUTTON_COUNT];
    private static float[] sPendingAxes = new float[AXIS_COUNT];

    // Accessed on the main thread only.
    private static Method sAddGamepad;
    private static Method sRemoveGamepad;
    private static Method sButtonChange;
    private static Method sAxisChange;
    private static byte[] sGamepadHandle;
    private static final float[] sLastButtons = new float[BUTTON_COUNT];
    private static final float[] sLastAxes = new float[AXIS_COUNT];
    private static long sRetryAddAtMs;

    private ControllerGamepadBridge() {}

    /** Called once per browser frame; input is copied and applied on the UI thread. */
    public static void update(boolean modeEnabled, boolean controllerAvailable,
                              float[] buttons, float[] axes) {
        synchronized (LOCK) {
            sRequested = modeEnabled;
            sControllerAvailable = controllerAvailable && buttons != null && axes != null
                    && buttons.length == BUTTON_COUNT && axes.length == AXIS_COUNT;
            if (sControllerAvailable) {
                sPendingButtons = Arrays.copyOf(buttons, BUTTON_COUNT);
                sPendingAxes = Arrays.copyOf(axes, AXIS_COUNT);
            } else {
                Arrays.fill(sPendingButtons, 0.0f);
                Arrays.fill(sPendingAxes, 0.0f);
            }
            scheduleApplyLocked();
        }
    }

    /** Immediately tears down the virtual device when the user disables the mode or the app pauses. */
    public static void onModeChanged(boolean enabled) {
        synchronized (LOCK) {
            sRequested = enabled;
            // Wait for a fresh OpenXR frame after enabling/resuming.
            sControllerAvailable = false;
            Arrays.fill(sPendingButtons, 0.0f);
            Arrays.fill(sPendingAxes, 0.0f);
            scheduleApplyLocked();
        }
    }

    private static void scheduleApplyLocked() {
        if (!sApplyQueued) {
            sApplyQueued = true;
            MAIN_HANDLER.postDelayed(APPLY_PENDING, UPDATE_DELAY_MS);
        }
    }

    private static final Runnable APPLY_PENDING = () -> {
        final boolean requested;
        final boolean available;
        final float[] buttons;
        final float[] axes;
        synchronized (LOCK) {
            requested = sRequested;
            available = sControllerAvailable;
            buttons = Arrays.copyOf(sPendingButtons, BUTTON_COUNT);
            axes = Arrays.copyOf(sPendingAxes, AXIS_COUNT);
            sApplyQueued = false;
        }
        applyState(requested && available, buttons, axes);
    };

    private static void applyState(boolean shouldConnect, float[] buttons, float[] axes) {
        if (!shouldConnect) {
            removeGamepad();
            return;
        }
        if (!loadMethods()) {
            return;
        }
        if (sGamepadHandle == null) {
            if (SystemClock.uptimeMillis() < sRetryAddAtMs) {
                return;
            }
            try {
                sGamepadHandle = (byte[]) sAddGamepad.invoke(null, "Wolvic OpenXR Controllers");
            } catch (IllegalAccessException | InvocationTargetException | ClassCastException e) {
                logBridgeFailure("Unable to create Gecko gamepad", e);
                sBridgeUnavailable = true;
                return;
            }
            if (sGamepadHandle == null) {
                // The Gecko Gamepad service may not be running until a page asks for gamepads.
                sRetryAddAtMs = SystemClock.uptimeMillis() + ADD_RETRY_DELAY_MS;
                return;
            }
            Arrays.fill(sLastButtons, 0.0f);
            Arrays.fill(sLastAxes, 0.0f);
        }

        for (int i = 0; i < BUTTON_COUNT; i++) {
            float value = clamp(buttons[i]);
            if (Math.abs(value - sLastButtons[i]) > 0.001f) {
                boolean pressed = value > 0.25f;
                try {
                    sButtonChange.invoke(null, sGamepadHandle, i, pressed, value);
                    sLastButtons[i] = value;
                } catch (IllegalAccessException | InvocationTargetException e) {
                    logBridgeFailure("Unable to update Gecko gamepad button", e);
                    sBridgeUnavailable = true;
                    removeGamepad();
                    return;
                }
            }
        }

        boolean[] changedAxes = new boolean[AXIS_COUNT];
        float[] axisValues = new float[AXIS_COUNT];
        boolean hasAxisChanges = false;
        for (int i = 0; i < AXIS_COUNT; i++) {
            float value = clampAxis(axes[i]);
            axisValues[i] = value;
            if (Math.abs(value - sLastAxes[i]) > 0.001f) {
                changedAxes[i] = true;
                hasAxisChanges = true;
            }
        }
        if (hasAxisChanges) {
            try {
                sAxisChange.invoke(null, sGamepadHandle, changedAxes, axisValues);
                for (int i = 0; i < AXIS_COUNT; i++) {
                    if (changedAxes[i]) {
                        sLastAxes[i] = axisValues[i];
                    }
                }
            } catch (IllegalAccessException | InvocationTargetException e) {
                logBridgeFailure("Unable to update Gecko gamepad axes", e);
                sBridgeUnavailable = true;
                removeGamepad();
            }
        }
    }

    private static boolean loadMethods() {
        if (sBridgeUnavailable) {
            return false;
        }
        if (sReflectionAttempted) {
            return true;
        }
        sReflectionAttempted = true;
        try {
            Class<?> manager = Class.forName(GECKO_MANAGER);
            sAddGamepad = manager.getDeclaredMethod("nativeAddGamepad", String.class);
            sRemoveGamepad = manager.getDeclaredMethod("nativeRemoveGamepad", byte[].class);
            sButtonChange = manager.getDeclaredMethod("onButtonChange", byte[].class,
                    int.class, boolean.class, float.class);
            sAxisChange = manager.getDeclaredMethod("onAxisChange", byte[].class,
                    boolean[].class, float[].class);
            sAddGamepad.setAccessible(true);
            sRemoveGamepad.setAccessible(true);
            sButtonChange.setAccessible(true);
            sAxisChange.setAccessible(true);
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException | SecurityException e) {
            logBridgeFailure("Gecko Android gamepad hooks are unavailable", e);
            sBridgeUnavailable = true;
            return false;
        }
    }

    private static void removeGamepad() {
        if (sGamepadHandle == null) {
            return;
        }
        if (sRemoveGamepad != null) {
            try {
                sRemoveGamepad.invoke(null, sGamepadHandle);
            } catch (IllegalAccessException | InvocationTargetException e) {
                logBridgeFailure("Unable to remove Gecko gamepad", e);
            }
        }
        sGamepadHandle = null;
        Arrays.fill(sLastButtons, 0.0f);
        Arrays.fill(sLastAxes, 0.0f);
        sRetryAddAtMs = 0;
    }

    private static float clamp(float value) {
        if (Float.isNaN(value)) {
            return 0.0f;
        }
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static float clampAxis(float value) {
        if (Float.isNaN(value)) {
            return 0.0f;
        }
        return Math.max(-1.0f, Math.min(1.0f, value));
    }

    private static void logBridgeFailure(String message, Exception error) {
        if (!sLoggedFailure) {
            sLoggedFailure = true;
            Log.w(TAG, message, error);
        }
    }
}
