package dev.module.statusbarbrightnessgesture;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Executors;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

@SuppressWarnings({"JavaReflectionMemberAccess", "ConstantConditions"})
public class BrightnessGestureHook implements IXposedHookLoadPackage {

    private static final String TAG = "BrightnessGestureHook";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    /**
     * Hook targets — confirmed working from v1.0:
     *
     * PhoneStatusBarView.onTouchEvent — shade CLOSED
     *   This method IS declared in DerpFest PhoneStatusBarView and receives
     *   all touch events on the status bar when the shade is closed.
     *   ev.getX() is in the view's local coordinates which span 0→screenWidth
     *   since PhoneStatusBarView fills the full width of the screen.
     *
     * NotificationShadeWindowView.dispatchTouchEvent — shade OPEN
     *   When QS/shade is open, touches go through this window instead.
     *   Apply Y threshold to only handle touches in the status bar region.
     *   ev.getX() here is also 0→screenWidth (the window is full-width).
     */
    private static final String PHONE_STATUS_BAR_VIEW =
            "com.android.systemui.statusbar.phone.PhoneStatusBarView";
    private static final String SHADE_WINDOW_CLASS =
            "com.android.systemui.shade.NotificationShadeWindowView";

    private static final float STATUS_BAR_Y_FRACTION = 0.06f;
    private static final float HORIZONTAL_RATIO = 2.0f;
    private static final float GAMMA = 2.2f;
    private static final long INDICATOR_DISMISS_DELAY_MS = 800;
    private static final long PREF_CACHE_MS = 1000;

    // ── Per-gesture state ─────────────────────────────────────────────────────

    private float mDownX;
    private float mDownY;
    private boolean mGestureActive = false;
    private boolean mTouchStartedInStatusBar = false;

    // ── Cached resources ──────────────────────────────────────────────────────

    private Context mContext;
    private DisplayManager mDisplayManager;
    private int mScreenWidth;
    private int mScreenHeight;
    private float mGestureSlopPx = 48f;
    private float mBrightnessMin = -1f;
    private float mBrightnessMax = 1.0f;

    private Method mSetTemporaryBrightnessMethod;
    private Method mSetBrightnessMethod;
    private Method mGetBrightnessInfoMethod;
    private Field mBrightnessField;
    private Field mBrightnessMinField;
    private Field mBrightnessMaxField;

    private final java.util.concurrent.ExecutorService mBgExecutor =
            Executors.newSingleThreadExecutor();
    private Handler mMainHandler;

    // ── Indicator ─────────────────────────────────────────────────────────────

    private TextView mIndicatorView;
    private WindowManager mWindowManager;
    private WindowManager.LayoutParams mIndicatorParams;
    private boolean mIndicatorAttached = false;
    private final Runnable mDismissIndicator = this::hideIndicator;

    // ── Prefs ─────────────────────────────────────────────────────────────────

    private boolean mGestureEnabled = true;
    private boolean mOverlayEnabled = true;
    private long mLastPrefReadMs = 0;

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

        // These two hooks are confirmed working in v1.0
        hookClass(PHONE_STATUS_BAR_VIEW, "onTouchEvent",
                lpparam.classLoader, hookMethodFn, true);
        hookClass(SHADE_WINDOW_CLASS, "dispatchTouchEvent",
                lpparam.classLoader, hookMethodFn, false);
    }

    private void hookClass(String className, String methodName, ClassLoader classLoader,
                           Method hookMethodFn, boolean isStatusBarView) {
        try {
            Class<?> targetClass = Class.forName(className, false, classLoader);
            Method targetMethod = targetClass.getDeclaredMethod(methodName, MotionEvent.class);

            hookMethodFn.invoke(null, targetMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    MotionEvent ev = (MotionEvent) param.args[0];
                    if (ev == null) return;

                    if (mDisplayManager == null) {
                        initResources(param.thisObject);
                    }

                    if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        refreshPrefs();
                    }

                    if (!mGestureEnabled) return;

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

    // ── Prefs ─────────────────────────────────────────────────────────────────

    private void refreshPrefs() {
        long now = System.currentTimeMillis();
        if (now - mLastPrefReadMs < PREF_CACHE_MS) return;
        mLastPrefReadMs = now;

        String content = readPlainTextPrefs();
        if (content == null) return;

        boolean gesture = true, overlay = true;
        for (String line : content.split("\n")) {
            String[] parts = line.split("=", 2);
            if (parts.length != 2) continue;
            String key = parts[0].trim(), val = parts[1].trim();
            if (Prefs.KEY_GESTURE_ENABLED.equals(key)) gesture = Boolean.parseBoolean(val);
            if (Prefs.KEY_OVERLAY_ENABLED.equals(key)) overlay = Boolean.parseBoolean(val);
        }
        mGestureEnabled = gesture;
        mOverlayEnabled = overlay;
        XposedBridge.log(TAG + ": prefs — gesture=" + gesture + " overlay=" + overlay);
    }

    private String readPlainTextPrefs() {
        String[] paths = {
                "/data/data/dev.module.statusbarbrightnessgesture/files/" + Prefs.PLAIN_TEXT_FILE,
                "/data/user/0/dev.module.statusbarbrightnessgesture/files/" + Prefs.PLAIN_TEXT_FILE,
                "/data/user_de/0/dev.module.statusbarbrightnessgesture/files/" + Prefs.PLAIN_TEXT_FILE,
        };
        for (String path : paths) {
            try {
                File f = new File(path);
                if (!f.exists()) continue;
                FileInputStream fis = new FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                fis.read(buf);
                fis.close();
                return new String(buf, "UTF-8");
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // ── Resource initialisation ───────────────────────────────────────────────

    private void initResources(Object viewInstance) {
        try {
            Context context = (Context) viewInstance.getClass()
                    .getMethod("getContext").invoke(viewInstance);
            if (context == null) return;

            mContext        = context;
            mMainHandler    = new Handler(Looper.getMainLooper());
            mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            mWindowManager  = (WindowManager)  context.getSystemService(Context.WINDOW_SERVICE);

            android.graphics.Rect bounds = mWindowManager.getCurrentWindowMetrics().getBounds();
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

            mGetBrightnessInfoMethod = Display.class.getDeclaredMethod("getBrightnessInfo");
            mGetBrightnessInfoMethod.setAccessible(true);

            readBrightnessRange();
            initIndicator(context);

            XposedBridge.log(TAG + ": init done — screen=" + mScreenWidth + "x" + mScreenHeight
                    + " range=[" + mBrightnessMin + ", " + mBrightnessMax + "]");

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": initResources failed: " + t);
        }
    }

    private void readBrightnessRange() {
        try {
            Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) return;
            Object info = mGetBrightnessInfoMethod.invoke(display);
            if (info == null) return;
            Class<?> cls = info.getClass();
            if (mBrightnessMinField == null) {
                mBrightnessField    = cls.getField("brightness");
                mBrightnessMinField = cls.getField("brightnessMinimum");
                mBrightnessMaxField = cls.getField("brightnessMaximum");
            }
            mBrightnessMin = (float) mBrightnessMinField.get(info);
            mBrightnessMax = (float) mBrightnessMaxField.get(info);
        } catch (Throwable t) {
            mBrightnessMin = 0.0f; mBrightnessMax = 1.0f;
            XposedBridge.log(TAG + ": readBrightnessRange fallback: " + t);
        }
    }

    // ── Indicator ─────────────────────────────────────────────────────────────

    private void initIndicator(Context context) {
        try {
            int accent  = resolveAccentColour(context);
            int textCol = getContrastingTextColour(accent);

            mIndicatorView = new TextView(context);
            mIndicatorView.setTextColor(textCol);
            mIndicatorView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
            mIndicatorView.setTypeface(Typeface.DEFAULT_BOLD);
            mIndicatorView.setGravity(Gravity.CENTER);

            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bg.setCornerRadius(100f);
            bg.setColor(accent);
            mIndicatorView.setBackground(bg);

            float d = context.getResources().getDisplayMetrics().density;
            mIndicatorView.setPadding((int)(14*d),(int)(6*d),(int)(14*d),(int)(6*d));

            mIndicatorParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            mIndicatorParams.gravity = Gravity.TOP | Gravity.START;
            mIndicatorView.setAlpha(0f);

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": initIndicator failed: " + t);
        }
    }

    private int resolveAccentColour(Context context) {
        try { return context.getColor(android.R.color.system_accent1_600); }
        catch (Throwable t) { return Color.argb(210, 30, 30, 30); }
    }

    private int getContrastingTextColour(int bg) {
        double r = Color.red(bg)/255.0, g = Color.green(bg)/255.0, b = Color.blue(bg)/255.0;
        r = r<=0.03928?r/12.92:Math.pow((r+0.055)/1.055,2.4);
        g = g<=0.03928?g/12.92:Math.pow((g+0.055)/1.055,2.4);
        b = b<=0.03928?b/12.92:Math.pow((b+0.055)/1.055,2.4);
        return (0.2126*r+0.7152*g+0.0722*b)<0.35 ? Color.WHITE : Color.BLACK;
    }

    /**
     * Shows the brightness % indicator.
     *
     * The percentage displayed is read directly from
     * "screen_brightness_float" — the exact same value
     * that Settings → Display reads to show the brightness percentage.
     * This guarantees our overlay always matches the system display.
     *
     * During a swipe, setTemporaryBrightness() updates the display hardware
     * but does not write to Settings. We therefore read the ContentResolver
     * for the last committed value as a fallback, and show the computed
     * value (linear → gamma) for live feedback during the swipe.
     *
     * @param fingerX  current finger X in view-local coordinates (0→screenWidth)
     * @param linearBrightness  the linear brightness float we just applied
     */
    private void showIndicator(float fingerX, float linearBrightness) {
        if (mIndicatorView == null || mWindowManager == null || mMainHandler == null) return;
        if (!mOverlayEnabled) return;

        mMainHandler.removeCallbacks(mDismissIndicator);

        // Read the native brightness % from Settings — same source as Settings→Display.
        // SCREEN_BRIGHTNESS_FLOAT stores the gamma-encoded value (0.0–1.0)
        // that the Settings app multiplies by 100 to show the percentage.
        // During the swipe setTemporaryBrightness hasn't written to Settings yet,
        // so we compute the same gamma encoding ourselves for live feedback.
        int pct;
        try {
            float stored = Settings.System.getFloat(
                    mContext.getContentResolver(),
                    "screen_brightness_float",
                    -1f);
            if (stored >= 0f) {
                // Settings stores the gamma-encoded float directly
                pct = Math.round(stored * 100f);
            } else {
                // Fallback: compute gamma-encoded value from the linear brightness
                // Android uses gamma≈2.2 for the QS/Settings display mapping
                float range = mBrightnessMax - mBrightnessMin;
                float normalised = range > 0
                        ? (linearBrightness - mBrightnessMin) / range
                        : linearBrightness;
                pct = Math.round((float) Math.pow(Math.max(0f, normalised), 1f / GAMMA) * 100f);
            }
        } catch (Throwable t) {
            // Safe fallback: finger position %
            pct = Math.round((fingerX / mScreenWidth) * 100f);
        }
        pct = Math.max(0, Math.min(100, pct));

        mIndicatorView.setText(pct + "%");
        mIndicatorView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(0,
                        android.view.View.MeasureSpec.UNSPECIFIED),
                android.view.View.MeasureSpec.makeMeasureSpec(0,
                        android.view.View.MeasureSpec.UNSPECIFIED));

        int viewW = mIndicatorView.getMeasuredWidth();
        int yOffset = (int)(mScreenHeight * 0.055f);
        int xOffset = Math.max(4, Math.min(mScreenWidth - viewW - 4,
                (int)(fingerX - viewW / 2f)));

        mIndicatorParams.x = xOffset;
        mIndicatorParams.y = yOffset;

        try {
            if (!mIndicatorAttached) {
                mWindowManager.addView(mIndicatorView, mIndicatorParams);
                mIndicatorAttached = true;
            } else {
                mWindowManager.updateViewLayout(mIndicatorView, mIndicatorParams);
            }
            mIndicatorView.setAlpha(1f);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": showIndicator failed: " + t);
        }
    }

    private void hideIndicator() {
        if (mIndicatorView == null || mMainHandler == null) return;
        mMainHandler.removeCallbacks(mDismissIndicator);
        mIndicatorView.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (mIndicatorAttached && mWindowManager != null) {
                try { mWindowManager.removeView(mIndicatorView); }
                catch (Throwable ignored) {}
                mIndicatorAttached = false;
            }
        }).start();
    }

    // ── Touch routing ─────────────────────────────────────────────────────────

    private boolean handleTouchEvent(MotionEvent ev, boolean isStatusBarView) {
        if (mDisplayManager == null || mScreenWidth == 0) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:   return onDown(ev, isStatusBarView);
            case MotionEvent.ACTION_MOVE:   return onMove(ev);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: return onUpOrCancel(ev);
            default: return false;
        }
    }

    private boolean onDown(MotionEvent ev, boolean isStatusBarView) {
        mGestureActive = false;
        mTouchStartedInStatusBar = false;

        // isStatusBarView=true: PhoneStatusBarView — the view IS the bar, accept all Y
        // isStatusBarView=false: NotificationShadeWindowView — apply Y threshold
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

        float absDX = Math.abs(ev.getX() - mDownX);
        float absDY = Math.abs(ev.getY() - mDownY);

        if (!mGestureActive) {
            if (absDX <= mGestureSlopPx || absDX <= absDY * HORIZONTAL_RATIO) return false;
            mGestureActive = true;
        }

        float brightness = computeBrightness(ev.getX());
        setTemporaryBrightness(brightness);
        showIndicator(ev.getX(), brightness);
        return true;
    }

    private boolean onUpOrCancel(MotionEvent ev) {
        if (!mGestureActive) { mTouchStartedInStatusBar = false; return false; }
        boolean cancelled = ev.getActionMasked() == MotionEvent.ACTION_CANCEL;
        float finalBrightness = cancelled
                ? getCurrentBrightness()
                : computeBrightness(ev.getX());
        setTemporaryBrightness(finalBrightness);
        commitBrightness(finalBrightness);
        if (mMainHandler != null)
            mMainHandler.postDelayed(mDismissIndicator, INDICATOR_DISMISS_DELAY_MS);
        mGestureActive = false;
        mTouchStartedInStatusBar = false;
        return true;
    }

    // ── Brightness computation ────────────────────────────────────────────────

    private float computeBrightness(float fingerX) {
        if (mBrightnessMin < 0) readBrightnessRange();
        float fraction = Math.max(0f, Math.min(1f, fingerX / mScreenWidth));
        float gammaCorrected = (float) Math.pow(fraction, GAMMA);
        return Math.max(mBrightnessMin,
                Math.min(mBrightnessMax,
                        mBrightnessMin + gammaCorrected * (mBrightnessMax - mBrightnessMin)));
    }

    // ── Hidden API calls ──────────────────────────────────────────────────────

    private void setTemporaryBrightness(float brightness) {
        if (mSetTemporaryBrightnessMethod == null) return;
        try { mSetTemporaryBrightnessMethod.invoke(
                mDisplayManager, Display.DEFAULT_DISPLAY, brightness); }
        catch (Throwable t) { XposedBridge.log(TAG + ": setTemporaryBrightness: " + t); }
    }

    private void commitBrightness(float brightness) {
        if (mSetBrightnessMethod == null) return;
        mBgExecutor.execute(() -> {
            try { mSetBrightnessMethod.invoke(
                    mDisplayManager, Display.DEFAULT_DISPLAY, brightness); }
            catch (Throwable t) { XposedBridge.log(TAG + ": setBrightness: " + t); }
        });
    }

    private float getCurrentBrightness() {
        try {
            if (mGetBrightnessInfoMethod == null || mBrightnessField == null) return 0.5f;
            Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) return 0.5f;
            Object info = mGetBrightnessInfoMethod.invoke(display);
            if (info == null) return 0.5f;
            float b = (float) mBrightnessField.get(info);
            return Math.max(mBrightnessMin, Math.min(mBrightnessMax, b));
        } catch (Throwable t) { return 0.5f; }
    }
}