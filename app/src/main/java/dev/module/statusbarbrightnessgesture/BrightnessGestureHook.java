package dev.module.statusbarbrightnessgesture;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Executors;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module — status bar horizontal swipe brightness gesture.
 *
 * Brightness range: reads brightnessMinimum and brightnessMaximum from BrightnessInfo,
 * exactly as BrightnessController does. This ensures the full swipe range matches the
 * QS slider range — left edge = same minimum as QS slider, right edge = same maximum.
 */
@SuppressWarnings({"JavaReflectionMemberAccess", "ConstantConditions"})
public class BrightnessGestureHook implements IXposedHookLoadPackage {

    private static final String TAG = "BrightnessGestureHook";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    private static final String PHONE_STATUS_BAR_VIEW =
            "com.android.systemui.statusbar.phone.PhoneStatusBarView";
    private static final String SHADE_WINDOW_CLASS =
            "com.android.systemui.shade.NotificationShadeWindowView";

    // ── Gesture tuning ────────────────────────────────────────────────────────

    private static final float STATUS_BAR_Y_FRACTION = 0.06f;
    private static final float HORIZONTAL_RATIO = 2.0f;
    private static final float GAMMA = 2.2f;

    // ── Per-gesture state (main thread only) ──────────────────────────────────

    private float mDownX;
    private float mDownY;
    private boolean mGestureActive = false;
    private boolean mTouchStartedInStatusBar = false;

    // ── Cached resources ──────────────────────────────────────────────────────

    private DisplayManager mDisplayManager;
    private int mScreenWidth;
    private int mScreenHeight;
    private float mGestureSlopPx = 48f;

    /**
     * The true minimum brightness for this display, read from BrightnessInfo.brightnessMinimum.
     * Matches the floor used by BrightnessController / QS slider.
     * Initialised to -1 to indicate "not yet read".
     */
    private float mBrightnessMin = -1f;

    /**
     * The true maximum brightness for this display, read from BrightnessInfo.brightnessMaximum.
     * Matches the ceiling used by BrightnessController / QS slider.
     */
    private float mBrightnessMax = 1.0f;

    private Method mSetTemporaryBrightnessMethod;
    private Method mSetBrightnessMethod;
    private Method mGetBrightnessInfoMethod;

    // BrightnessInfo fields — cached after first read
    private Field mBrightnessField;
    private Field mBrightnessMinField;
    private Field mBrightnessMaxField;

    private final java.util.concurrent.ExecutorService mBgExecutor =
            Executors.newSingleThreadExecutor();

    // ── Entry point ───────────────────────────────────────────────────────────

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!SYSTEMUI_PACKAGE.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": loading in SystemUI");

        Method hookMethodFn = findHookMethod();
        if (hookMethodFn == null) {
            XposedBridge.log(TAG + ": could not find hookMethod() — aborting");
            return;
        }

        hookClass(PHONE_STATUS_BAR_VIEW, "onTouchEvent",
                lpparam.classLoader, hookMethodFn, true);
        hookClass(SHADE_WINDOW_CLASS, "dispatchTouchEvent",
                lpparam.classLoader, hookMethodFn, false);
    }

    private void hookClass(String className, String methodName, ClassLoader classLoader,
                           Method hookMethodFn, boolean isStatusBarView) {
        try {
            Class<?> targetClass = Class.forName(className, false, classLoader);
            Method targetMethod = targetClass
                    .getDeclaredMethod(methodName, MotionEvent.class);

            hookMethodFn.invoke(null, targetMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    MotionEvent ev = (MotionEvent) param.args[0];
                    if (ev == null) return;

                    if (mDisplayManager == null) {
                        initResources(param.thisObject);
                    }

                    if (handleTouchEvent(ev, isStatusBarView)) {
                        param.setResult(true);
                    }
                }
            });

            XposedBridge.log(TAG + ": hooked " + className + "." + methodName);

        } catch (ClassNotFoundException e) {
            XposedBridge.log(TAG + ": class not found: " + className);
        } catch (NoSuchMethodException e) {
            XposedBridge.log(TAG + ": " + methodName + " not found in: " + className);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook " + className + ": " + t);
        }
    }

    private Method findHookMethod() {
        for (Method m : XposedBridge.class.getDeclaredMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 2
                    && java.lang.reflect.Member.class.isAssignableFrom(params[0])
                    && XC_MethodHook.class.isAssignableFrom(params[1])) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    // ── Resource initialisation ───────────────────────────────────────────────

    private void initResources(Object viewInstance) {
        try {
            Context context = (Context) viewInstance.getClass()
                    .getMethod("getContext")
                    .invoke(viewInstance);

            if (context == null) {
                XposedBridge.log(TAG + ": initResources — getContext() returned null");
                return;
            }

            mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);

            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            android.graphics.Rect bounds = wm.getCurrentWindowMetrics().getBounds();
            mScreenWidth  = bounds.width();
            mScreenHeight = bounds.height();

            float density = context.getResources().getDisplayMetrics().density;
            mGestureSlopPx = Math.max(
                    ViewConfiguration.get(context).getScaledTouchSlop(),
                    12f * density);

            mSetTemporaryBrightnessMethod = DisplayManager.class
                    .getDeclaredMethod("setTemporaryBrightness", int.class, float.class);
            mSetTemporaryBrightnessMethod.setAccessible(true);

            mSetBrightnessMethod = DisplayManager.class
                    .getDeclaredMethod("setBrightness", int.class, float.class);
            mSetBrightnessMethod.setAccessible(true);

            mGetBrightnessInfoMethod = Display.class
                    .getDeclaredMethod("getBrightnessInfo");
            mGetBrightnessInfoMethod.setAccessible(true);

            // Read the true min/max from BrightnessInfo — same source as BrightnessController
            readBrightnessRange();

            XposedBridge.log(TAG + ": init done — screen=" + mScreenWidth + "x" + mScreenHeight
                    + " slopPx=" + mGestureSlopPx
                    + " brightnessRange=[" + mBrightnessMin + ", " + mBrightnessMax + "]");

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": initResources failed: " + t);
        }
    }

    /**
     * Reads brightnessMinimum and brightnessMaximum from BrightnessInfo.
     *
     * BrightnessController reads these same fields in updateBrightnessInfo():
     *   mBrightnessMax = info.brightnessMaximum;
     *   mBrightnessMin = info.brightnessMinimum;
     *
     * This ensures our swipe range exactly matches the QS slider range.
     * On Oriole/Pixel 6, brightnessMinimum is typically ~0.0 and
     * brightnessMaximum is 1.0, but the exact values are display-specific.
     */
    private void readBrightnessRange() {
        try {
            Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) return;

            Object info = mGetBrightnessInfoMethod.invoke(display);
            if (info == null) return;

            Class<?> infoClass = info.getClass();

            // Cache field references on first use
            if (mBrightnessMinField == null) {
                mBrightnessField    = infoClass.getField("brightness");
                mBrightnessMinField = infoClass.getField("brightnessMinimum");
                mBrightnessMaxField = infoClass.getField("brightnessMaximum");
            }

            mBrightnessMin = (float) mBrightnessMinField.get(info);
            mBrightnessMax = (float) mBrightnessMaxField.get(info);

            XposedBridge.log(TAG + ": brightness range from BrightnessInfo: ["
                    + mBrightnessMin + ", " + mBrightnessMax + "]");

        } catch (Throwable t) {
            // Fallback to safe defaults if BrightnessInfo fields aren't available
            mBrightnessMin = 0.0f;
            mBrightnessMax = 1.0f;
            XposedBridge.log(TAG + ": readBrightnessRange failed, using defaults: " + t);
        }
    }

    // ── Touch routing ─────────────────────────────────────────────────────────

    private boolean handleTouchEvent(MotionEvent ev, boolean isStatusBarView) {
        if (mDisplayManager == null || mScreenWidth == 0) return false;

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return onDown(ev, isStatusBarView);
            case MotionEvent.ACTION_MOVE:
                return onMove(ev);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return onUpOrCancel(ev);
            default:
                return false;
        }
    }

    private boolean onDown(MotionEvent ev, boolean isStatusBarView) {
        mGestureActive = false;
        mTouchStartedInStatusBar = false;

        boolean inRegion = isStatusBarView
                || (ev.getY() <= mScreenHeight * STATUS_BAR_Y_FRACTION);

        if (!inRegion) return false;

        mTouchStartedInStatusBar = true;
        mDownX = ev.getX();
        mDownY = ev.getY();
        return false;
    }

    private boolean onMove(MotionEvent ev) {
        if (!mTouchStartedInStatusBar) return false;

        float deltaX = ev.getX() - mDownX;
        float absDX = Math.abs(deltaX);
        float absDY = Math.abs(ev.getY() - mDownY);

        if (!mGestureActive) {
            if (absDX <= mGestureSlopPx || absDX <= absDY * HORIZONTAL_RATIO) return false;
            mGestureActive = true;
        }

        setTemporaryBrightness(computeBrightness(ev.getX()));
        return true;
    }

    private boolean onUpOrCancel(MotionEvent ev) {
        if (!mGestureActive) {
            mTouchStartedInStatusBar = false;
            return false;
        }

        boolean cancelled = ev.getActionMasked() == MotionEvent.ACTION_CANCEL;
        float finalBrightness = cancelled
                ? getCurrentBrightness()
                : computeBrightness(ev.getX());

        setTemporaryBrightness(finalBrightness);
        commitBrightness(finalBrightness);

        mGestureActive = false;
        mTouchStartedInStatusBar = false;
        return true;
    }

    // ── Brightness computation ────────────────────────────────────────────────

    /**
     * Maps absolute finger X to a brightness float in [mBrightnessMin, mBrightnessMax].
     *
     * Mirrors BrightnessController's use of brightnessMinimum / brightnessMaximum
     * so the gesture range exactly matches the QS slider range:
     *   finger at left edge  → mBrightnessMin  (same as QS slider leftmost)
     *   finger at right edge → mBrightnessMax  (same as QS slider rightmost)
     *
     * Gamma curve for perceptual linearity, same as convertGammaToLinearFloat().
     */
    private float computeBrightness(float fingerX) {
        // Ensure we have valid range values
        if (mBrightnessMin < 0) readBrightnessRange();

        float fraction = fingerX / mScreenWidth;
        float clamped = Math.max(0f, Math.min(1f, fraction));

        // Gamma correction for perceptual linearity
        float gammaCorrected = (float) Math.pow(clamped, GAMMA);

        // Map to the display's actual brightness range
        float brightness = mBrightnessMin + gammaCorrected * (mBrightnessMax - mBrightnessMin);
        return Math.max(mBrightnessMin, Math.min(mBrightnessMax, brightness));
    }

    // ── Hidden API calls ──────────────────────────────────────────────────────

    private void setTemporaryBrightness(float brightness) {
        if (mSetTemporaryBrightnessMethod == null) return;
        try {
            mSetTemporaryBrightnessMethod.invoke(
                    mDisplayManager, Display.DEFAULT_DISPLAY, brightness);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setTemporaryBrightness failed: " + t);
        }
    }

    private void commitBrightness(float brightness) {
        if (mSetBrightnessMethod == null) return;
        mBgExecutor.execute(() -> {
            try {
                mSetBrightnessMethod.invoke(
                        mDisplayManager, Display.DEFAULT_DISPLAY, brightness);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": setBrightness failed: " + t);
            }
        });
    }

    private float getCurrentBrightness() {
        if (mGetBrightnessInfoMethod == null) return 0.5f;
        try {
            Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) return 0.5f;
            Object info = mGetBrightnessInfoMethod.invoke(display);
            if (info == null) return 0.5f;
            if (mBrightnessField == null) return 0.5f;
            float b = (float) mBrightnessField.get(info);
            return Math.max(mBrightnessMin, Math.min(mBrightnessMax, b));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getCurrentBrightness failed: " + t);
            return 0.5f;
        }
    }
}