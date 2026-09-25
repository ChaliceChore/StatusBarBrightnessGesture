package dev.module.statusbarbrightnessgesture;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Executors;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module — status bar brightness gesture.
 *
 * Hooking: findHookMethod() reflects into XposedBridge at runtime to bypass
 * LSPosed's obfuscation of hookMethod().
 *
 * Prefs: broadcast approach.
 *   - SettingsActivity writes to SharedPreferences and sends a targeted
 *     broadcast to com.android.systemui with the new values as extras.
 *   - Hook registers a BroadcastReceiver inside SystemUI from onAttachedToWindow —
 *     guaranteed safe timing, no race condition.
 *   - SettingsActivity also re-sends on onResume() so values survive SystemUI restarts.
 *   - No permissions needed.
 */
@SuppressWarnings({"JavaReflectionMemberAccess", "ConstantConditions"})
public class BrightnessGestureHook implements IXposedHookLoadPackage {

    private static final String TAG = "BrightnessGestureHook";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    private static final String PHONE_STATUS_BAR_VIEW =
            "com.android.systemui.statusbar.phone.PhoneStatusBarView";
    private static final String SHADE_WINDOW_CLASS =
            "com.android.systemui.shade.NotificationShadeWindowView";
    private static final String BRIGHTNESS_UTILS_CLASS =
            "com.android.settingslib.display.BrightnessUtils";
    /**
     * Root of a Compose hierarchy. From Android 17 the status bar window is
     * Compose, with the legacy PhoneStatusBarView embedded in it through
     * PointerInteropFilter; see hookComposeRoot() for why that matters.
     */
    private static final String COMPOSE_ROOT_CLASS =
            "androidx.compose.ui.platform.AndroidComposeView";

    private static final int GAMMA_SPACE_MAX = 65535;
    private static final float STATUS_BAR_Y_FRACTION = 0.06f;
    private static final float HORIZONTAL_RATIO = 2.0f;
    private static final float GAMMA = 2.2f;
    private static final long INDICATOR_DISMISS_DELAY_MS = 800;

    /** Which hooked method a touch arrived through. */
    private static final int TOUCH_SOURCE_NONE       = 0;
    private static final int TOUCH_SOURCE_STATUS_BAR = 1;
    private static final int TOUCH_SOURCE_SHADE      = 2;
    private static final int TOUCH_SOURCE_COMPOSE    = 3;

    /** What the caller should do with an event after handleTouchEvent(). */
    private static final int TOUCH_PASS              = 0;
    private static final int TOUCH_CONSUME           = 1;
    private static final int TOUCH_CANCEL_UNDERLYING = 2;

    // ── Per-gesture state ─────────────────────────────────────────────────────

    private float mDownX;
    private float mDownY;
    private boolean mGestureActive = false;
    private boolean mTouchStartedInStatusBar = false;

    /**
     * The Compose root of the status bar window, resolved by walking up from
     * PhoneStatusBarView once it is attached. Weak so a torn-down status bar
     * (SystemUI rebuilds it on a theme or density change) can be collected.
     */
    private WeakReference<View> mStatusBarComposeRoot;

    /** Hook that owns the gesture in progress, or TOUCH_SOURCE_NONE. */
    private int mGestureOwner = TOUCH_SOURCE_NONE;
    /** downTime of the touch mGestureOwner claimed — identifies the same
     *  physical gesture when it also arrives through the other hook. */
    private long mOwnedDownTime = -1;
    /** Cancel event passed to the original method; recycled once it returns. */
    private MotionEvent mPendingCancel;

    // ── Cached resources ──────────────────────────────────────────────────────

    private DisplayManager mDisplayManager;
    private KeyguardManager mKeyguardManager;
    private WindowManager mWindowManager;
    private int mScreenWidth;
    private int mScreenHeight;
    private float mGestureSlopPx = 48f;
    private float mBrightnessMin = -1f;
    private float mBrightnessMax = 1.0f;

    private Method mSetTemporaryBrightnessMethod;
    private Method mSetBrightnessMethod;
    private Method mGetBrightnessInfoMethod;
    private Method mConvertLinearToGammaMethod;

    private Field mBrightnessField;
    private Field mBrightnessMinField;
    private Field mBrightnessMaxField;

    private final java.util.concurrent.ExecutorService mBgExecutor =
            Executors.newSingleThreadExecutor();
    private Handler mMainHandler;

    // ── Indicator ─────────────────────────────────────────────────────────────

    private TextView mIndicatorView;
    private WindowManager.LayoutParams mIndicatorParams;
    private boolean mIndicatorAttached = false;
    private final Runnable mDismissIndicator = this::hideIndicator;

    // ── Prefs ─────────────────────────────────────────────────────────────────

    private boolean mReceiverRegistered = false;
    private volatile boolean mGestureEnabled = true;
    private volatile boolean mOverlayEnabled  = true;
    private volatile boolean mRelativeMode    = false;
    private volatile boolean mLockscreenEnabled = true;

    /**
     * Where the gesture's travel is anchored in relative mode: the position on
     * the 0..1 curve that was showing when the gesture was recognised, and the
     * x it was recognised at. Unused in absolute mode.
     */
    private float mGestureStartFraction;
    private float mRelativeAnchorX;

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

        hookTouchTarget(PHONE_STATUS_BAR_VIEW, "onTouchEvent",
                lpparam.classLoader, hookMethodFn, true);
        hookTouchTarget(SHADE_WINDOW_CLASS, "dispatchTouchEvent",
                lpparam.classLoader, hookMethodFn, false);
        hookAttachedToWindow(PHONE_STATUS_BAR_VIEW,
                lpparam.classLoader, hookMethodFn);
        hookComposeRoot(lpparam.classLoader, hookMethodFn);
    }

    // ── Runtime reflection to find LSPosed's real hookMethod ──────────────────

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

    // ── onAttachedToWindow — register receiver and init resources ─────────────

    private void hookAttachedToWindow(String className, ClassLoader classLoader,
                                      Method hookMethodFn) {
        try {
            Class<?> cls = Class.forName(className, false, classLoader);
            Method target = cls.getDeclaredMethod("onAttachedToWindow");
            hookMethodFn.invoke(null, target, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Context ctx = (Context) param.thisObject.getClass()
                                .getMethod("getContext").invoke(param.thisObject);
                        if (ctx == null) return;
                        if (!mReceiverRegistered) registerPrefsReceiver(ctx);
                        if (mDisplayManager == null) initDisplayResources(ctx);
                        cacheComposeRoot(param.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": onAttachedToWindow init failed: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked " + className + ".onAttachedToWindow");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook onAttachedToWindow: " + t);
        }
    }

    // ── Broadcast receiver for prefs ──────────────────────────────────────────

    private void registerPrefsReceiver(Context context) {
        if (mReceiverRegistered) return;
        mReceiverRegistered = true;

        // Read persisted state from Settings.Secure on boot — available immediately,
        // no app process needed, survives reboots.
        // Falls back to true (enabled) if the key doesn't exist yet.
        try {
            mGestureEnabled = Settings.Secure.getInt(context.getContentResolver(),
                    Prefs.KEY_GESTURE_ENABLED, Prefs.DEFAULT_GESTURE_ENABLED) == 1;
            mOverlayEnabled = Settings.Secure.getInt(context.getContentResolver(),
                    Prefs.KEY_OVERLAY_ENABLED, Prefs.DEFAULT_OVERLAY_ENABLED) == 1;
            mRelativeMode = Settings.Secure.getInt(context.getContentResolver(),
                    Prefs.KEY_RELATIVE_MODE, Prefs.DEFAULT_RELATIVE_MODE) == 1;
            mLockscreenEnabled = Settings.Secure.getInt(context.getContentResolver(),
                    Prefs.KEY_LOCKSCREEN_ENABLED,
                    Prefs.DEFAULT_LOCKSCREEN_ENABLED) == 1;
            XposedBridge.log(TAG + ": boot state from Settings.Secure — gesture="
                    + mGestureEnabled + " overlay=" + mOverlayEnabled
                    + " relative=" + mRelativeMode
                    + " lockscreen=" + mLockscreenEnabled);
        } catch (Throwable t) {
            mGestureEnabled = true;
            mOverlayEnabled  = true;
            mRelativeMode    = Prefs.DEFAULT_RELATIVE_MODE == 1;
            mLockscreenEnabled = Prefs.DEFAULT_LOCKSCREEN_ENABLED == 1;
            XposedBridge.log(TAG + ": Settings.Secure read failed, defaulting to true: " + t);
        }

        // Broadcast receiver for live updates when user changes a toggle
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!Prefs.ACTION_PREFS_CHANGED.equals(intent.getAction())) return;
                boolean prevGesture = mGestureEnabled;
                mGestureEnabled = intent.getBooleanExtra(Prefs.KEY_GESTURE_ENABLED, true);
                mOverlayEnabled  = intent.getBooleanExtra(Prefs.KEY_OVERLAY_ENABLED,  true);
                mRelativeMode    = intent.getBooleanExtra(Prefs.KEY_RELATIVE_MODE,
                        Prefs.DEFAULT_RELATIVE_MODE == 1);
                mLockscreenEnabled = intent.getBooleanExtra(
                        Prefs.KEY_LOCKSCREEN_ENABLED,
                        Prefs.DEFAULT_LOCKSCREEN_ENABLED == 1);
                XposedBridge.log(TAG + ": prefs updated via broadcast — gesture="
                        + mGestureEnabled + " overlay=" + mOverlayEnabled
                        + " relative=" + mRelativeMode
                        + " lockscreen=" + mLockscreenEnabled);
                if (prevGesture && !mGestureEnabled && mIndicatorAttached) {
                    hideIndicator();
                }
            }
        };

        IntentFilter filter = new IntentFilter(Prefs.ACTION_PREFS_CHANGED);
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        XposedBridge.log(TAG + ": prefs receiver registered");
    }

    // ── Touch hook setup ──────────────────────────────────────────────────────

    private void hookTouchTarget(String className, String methodName,
                                 ClassLoader classLoader, Method hookMethodFn,
                                 boolean isStatusBarView) {
        try {
            Class<?> cls = Class.forName(className, false, classLoader);
            Method target = cls.getDeclaredMethod(methodName, MotionEvent.class);
            final int source = isStatusBarView
                    ? TOUCH_SOURCE_STATUS_BAR : TOUCH_SOURCE_SHADE;
            hookMethodFn.invoke(null, target, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    routeTouch(param, source);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    recyclePendingCancel();
                }
            });
            XposedBridge.log(TAG + ": hooked " + className + "." + methodName);
        } catch (ClassNotFoundException e) {
            XposedBridge.log(TAG + ": class not found: " + className);
        } catch (NoSuchMethodException e) {
            XposedBridge.log(TAG + ": method not found: " + methodName);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook failed for " + className + ": " + t);
        }
    }

    /**
     * From Android 17 the status bar window is a Compose hierarchy with the
     * legacy PhoneStatusBarView embedded through PointerInteropFilter. That
     * filter only keeps feeding a wrapped View while the View consumes what it
     * is given. This module deliberately does not consume the down or the early
     * moves, so that taps and shade pull-downs keep working, so the filter
     * calls stopDispatching() and synthesises ACTION_CANCEL into
     * PhoneStatusBarView right after the first move. The gesture never gets far
     * enough to cross the activation slop, so nothing happens at all.
     *
     * The window's Compose root, by contrast, sees the whole gesture. Hooking
     * its dispatchTouchEvent() puts this module above the interop filter, where
     * consuming an event stops Compose from processing it and passing one on
     * leaves Compose's own gestures — the pull-down among them — untouched.
     * That is the same contract the legacy hooks rely on, so the routing in
     * handleTouchEvent() is unchanged.
     *
     * Android 16 and earlier have no Compose status bar: the class is absent,
     * no root is ever cached, and the legacy hooks keep doing the work.
     */
    private void hookComposeRoot(ClassLoader classLoader, Method hookMethodFn) {
        try {
            Class<?> cls = Class.forName(COMPOSE_ROOT_CLASS, false, classLoader);
            Method target = cls.getDeclaredMethod(
                    "dispatchTouchEvent", MotionEvent.class);
            hookMethodFn.invoke(null, target, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isStatusBarComposeRoot(param.thisObject)) return;
                    routeTouch(param, TOUCH_SOURCE_COMPOSE);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    recyclePendingCancel();
                }
            });
            XposedBridge.log(TAG + ": hooked " + COMPOSE_ROOT_CLASS
                    + ".dispatchTouchEvent");
        } catch (ClassNotFoundException e) {
            XposedBridge.log(TAG + ": no Compose status bar — legacy hooks only");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook Compose root: " + t);
        }
    }

    /**
     * Walks up from the attached PhoneStatusBarView to the Compose root hosting
     * it, so the dispatch hook can tell the status bar window apart from every
     * other Compose window in SystemUI.
     */
    private void cacheComposeRoot(Object statusBarView) {
        try {
            if (!(statusBarView instanceof View)) return;
            for (ViewParent parent = ((View) statusBarView).getParent();
                    parent != null; parent = parent.getParent()) {
                if (COMPOSE_ROOT_CLASS.equals(parent.getClass().getName())) {
                    mStatusBarComposeRoot = new WeakReference<>((View) parent);
                    XposedBridge.log(TAG + ": Compose status bar root cached");
                    return;
                }
            }
            mStatusBarComposeRoot = null;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": cacheComposeRoot failed: " + t);
        }
    }

    private boolean isStatusBarComposeRoot(Object view) {
        return mStatusBarComposeRoot != null && mStatusBarComposeRoot.get() == view;
    }

    // ── Shared routing for every touch hook ──────────────────────────────────

    private void routeTouch(XC_MethodHook.MethodHookParam param, int source) {
        MotionEvent ev = (MotionEvent) param.args[0];
        if (ev == null) return;
        if (mDisplayManager == null) {
            try {
                Context ctx = (Context) param.thisObject.getClass()
                        .getMethod("getContext").invoke(param.thisObject);
                if (ctx != null) initDisplayResources(ctx);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": display init failed: " + t);
            }
        }
        if (!mGestureEnabled) return;

        switch (handleTouchEvent(ev, source)) {
            case TOUCH_CONSUME:
                param.setResult(true);
                break;
            case TOUCH_CANCEL_UNDERLYING:
                // Let the original method run, but hand it a cancel in place
                // of this move — see onMove() for why.
                MotionEvent cancel = MotionEvent.obtain(ev);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                mPendingCancel = cancel;
                param.args[0] = cancel;
                break;
            default:
                break;
        }
    }

    private void recyclePendingCancel() {
        if (mPendingCancel != null) {
            mPendingCancel.recycle();
            mPendingCancel = null;
        }
    }

    // ── Display resource initialisation ──────────────────────────────────────

    private void initDisplayResources(Context context) {
        try {
            if (mMainHandler == null) mMainHandler = new Handler(Looper.getMainLooper());
            mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            mWindowManager  = (WindowManager)  context.getSystemService(Context.WINDOW_SERVICE);
            mKeyguardManager = (KeyguardManager)
                    context.getSystemService(Context.KEYGUARD_SERVICE);

            android.graphics.Rect bounds = mWindowManager.getCurrentWindowMetrics().getBounds();
            mScreenWidth  = bounds.width();
            mScreenHeight = bounds.height();

            float density = context.getResources().getDisplayMetrics().density;
            mGestureSlopPx = Math.max(
                    ViewConfiguration.get(context).getScaledTouchSlop(), 12f * density);

            mSetTemporaryBrightnessMethod = DisplayManager.class
                    .getDeclaredMethod("setTemporaryBrightness", int.class, float.class);
            mSetTemporaryBrightnessMethod.setAccessible(true);

            mSetBrightnessMethod = DisplayManager.class
                    .getDeclaredMethod("setBrightness", int.class, float.class);
            mSetBrightnessMethod.setAccessible(true);

            mGetBrightnessInfoMethod = Display.class.getDeclaredMethod("getBrightnessInfo");
            mGetBrightnessInfoMethod.setAccessible(true);

            try {
                Class<?> bu = Class.forName(
                        BRIGHTNESS_UTILS_CLASS, false, context.getClassLoader());
                mConvertLinearToGammaMethod = bu.getMethod(
                        "convertLinearToGammaFloat", float.class, float.class, float.class);
                XposedBridge.log(TAG + ": found BrightnessUtils.convertLinearToGammaFloat");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": BrightnessUtils not found, using fallback");
            }

            readBrightnessRange();
            initIndicator(context);

            XposedBridge.log(TAG + ": display init — screen=" + mScreenWidth
                    + "x" + mScreenHeight
                    + " range=[" + mBrightnessMin + ", " + mBrightnessMax + "]");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": initDisplayResources failed: " + t);
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
            mBrightnessMin = 0.0f;
            mBrightnessMax = 1.0f;
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
            mIndicatorView.setPadding(
                    (int)(14*d), (int)(6*d), (int)(14*d), (int)(6*d));

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
        r = r<=0.03928?r/12.92:Math.pow((r+0.055)/1.055, 2.4);
        g = g<=0.03928?g/12.92:Math.pow((g+0.055)/1.055, 2.4);
        b = b<=0.03928?b/12.92:Math.pow((b+0.055)/1.055, 2.4);
        return (0.2126*r + 0.7152*g + 0.0722*b) < 0.35 ? Color.WHITE : Color.BLACK;
    }

    private void showIndicator(float fingerX, float linearBrightness) {
        if (mIndicatorView == null || mWindowManager == null || mMainHandler == null) return;
        if (!mOverlayEnabled) return;

        mMainHandler.removeCallbacks(mDismissIndicator);

        int pct = linearToDisplayPct(linearBrightness);
        mIndicatorView.setText(pct + "%");
        mIndicatorView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(0,
                        android.view.View.MeasureSpec.UNSPECIFIED),
                android.view.View.MeasureSpec.makeMeasureSpec(0,
                        android.view.View.MeasureSpec.UNSPECIFIED));

        int viewW   = mIndicatorView.getMeasuredWidth();
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

    private int linearToDisplayPct(float linear) {
        try {
            if (mConvertLinearToGammaMethod != null) {
                int gammaVal = (int) mConvertLinearToGammaMethod.invoke(
                        null, linear, mBrightnessMin, mBrightnessMax);
                return Math.max(0, Math.min(100,
                        Math.round((float) gammaVal / GAMMA_SPACE_MAX * 100f)));
            }
        } catch (Throwable ignored) {}
        float range = mBrightnessMax - mBrightnessMin;
        if (range <= 0) return 0;
        float n = Math.max(0f, Math.min(1f, (linear - mBrightnessMin) / range));
        return Math.max(0, Math.min(100,
                Math.round((float) Math.pow(n, 1.0 / GAMMA) * 100f)));
    }

    /**
      * Inverse of the curve computeBrightness() applies: the position on the
      * gesture's 0..1 travel that currently shows this brightness. Used to
      * anchor relative mode at the level already on screen.
      */
    private float brightnessToFraction(float brightness) {
        float range = mBrightnessMax - mBrightnessMin;
        if (range <= 0) return 0f;
        float normalised = Math.max(0f, Math.min(1f,
                (brightness - mBrightnessMin) / range));
        return (float) Math.pow(normalised, 1.0 / GAMMA);
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

    private int handleTouchEvent(MotionEvent ev, int source) {
        if (mDisplayManager == null || mScreenWidth == 0) return TOUCH_PASS;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:   return onDown(ev, source);
            case MotionEvent.ACTION_MOVE:   return onMove(ev, source);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: return onUpOrCancel(ev, source);
            default: return TOUCH_PASS;
        }
    }

    private int onDown(MotionEvent ev, int source) {
        // One physical touch can reach both hooks: PhoneStatusBarView.onTouchEvent
        // and NotificationShadeWindowView.dispatchTouchEvent sit on the same
        // delivery path while the shade is open. They share this object's state,
        // so without an owner check the second DOWN would clear the gesture the
        // first one just started. downTime is identical for both copies.
        if (mGestureOwner != TOUCH_SOURCE_NONE
                && mGestureOwner != source
                && mOwnedDownTime == ev.getDownTime()) {
            return TOUCH_PASS;
        }

        releaseGesture();

        // Checked per gesture rather than cached, since the lockscreen comes
        // and goes while SystemUI keeps running.
        if (!mLockscreenEnabled && isKeyguardShowing()) return TOUCH_PASS;

        boolean inRegion = source == TOUCH_SOURCE_STATUS_BAR
                || (ev.getY() <= mScreenHeight * STATUS_BAR_Y_FRACTION);
        if (!inRegion) return TOUCH_PASS;

        mTouchStartedInStatusBar = true;
        mGestureOwner = source;
        mOwnedDownTime = ev.getDownTime();
        mDownX = ev.getX();
        mDownY = ev.getY();
        // Not consumed on purpose: taps and vertical pull-downs must still reach
        // SystemUI. The horizontal gesture is only recognised on MOVE.
        return TOUCH_PASS;
    }

    private int onMove(MotionEvent ev, int source) {
        if (!mTouchStartedInStatusBar || source != mGestureOwner) return TOUCH_PASS;
        float absDX = Math.abs(ev.getX() - mDownX);
        float absDY = Math.abs(ev.getY() - mDownY);
        boolean justActivated = false;
        if (!mGestureActive) {
            if (absDX <= mGestureSlopPx || absDX <= absDY * HORIZONTAL_RATIO) {
                return TOUCH_PASS;
            }
            mGestureActive = true;
            justActivated = true;
            if (mRelativeMode) {
                // Anchor the travel where the gesture was recognised, not at
                // the down, so the first value applied is the brightness
                // already on screen rather than a jump of one touch slop.
                mGestureStartFraction = brightnessToFraction(getCurrentBrightness());
                mRelativeAnchorX = ev.getX();
            }
        }
        float brightness = computeBrightness(ev.getX());
        setTemporaryBrightness(brightness);
        showIndicator(ev.getX(), brightness);

        // SystemUI started tracking a shade expansion back on ACTION_DOWN, which
        // this hook deliberately let through so taps and pull-downs keep working.
        // If the gesture now simply swallows every following event, that tracking
        // is never ended and the shade settles open when the finger lifts. So on
        // the event that recognises the gesture, hand ACTION_CANCEL to the
        // original method to abort the expansion; everything after is consumed.
        return justActivated ? TOUCH_CANCEL_UNDERLYING : TOUCH_CONSUME;
    }

    private int onUpOrCancel(MotionEvent ev, int source) {
        if (source != mGestureOwner) return TOUCH_PASS;
        if (!mGestureActive) {
            releaseGesture();
            return TOUCH_PASS;
        }
        boolean cancelled = ev.getActionMasked() == MotionEvent.ACTION_CANCEL;
        float finalBrightness = cancelled
                ? getCurrentBrightness()
                : computeBrightness(ev.getX());
        setTemporaryBrightness(finalBrightness);
        commitBrightness(finalBrightness);
        if (mMainHandler != null)
            mMainHandler.postDelayed(mDismissIndicator, INDICATOR_DISMISS_DELAY_MS);
        releaseGesture();
        return TOUCH_CONSUME;
    }

    private boolean isKeyguardShowing() {
        try {
            return mKeyguardManager != null && mKeyguardManager.isKeyguardLocked();
        } catch (Throwable t) {
            // Cannot tell — treat as unlocked rather than killing the gesture.
            return false;
        }
    }

    /** Clears all per-gesture state, including hook ownership. */
    private void releaseGesture() {
        mGestureActive = false;
        mTouchStartedInStatusBar = false;
        mGestureOwner = TOUCH_SOURCE_NONE;
        mOwnedDownTime = -1;
        mGestureStartFraction = 0f;
        mRelativeAnchorX = 0f;
    }

    // ── Brightness computation ────────────────────────────────────────────────

    private float computeBrightness(float fingerX) {
        if (mBrightnessMin < 0) readBrightnessRange();
        // Absolute: the finger's position on the bar *is* the brightness.
        // Relative: how far the finger has travelled since the gesture was
        // recognised is added to the brightness that was already showing. Both
        // share the same curve, so a full-width swipe covers the full range
        // either way.
        float fraction = mRelativeMode
                ? mGestureStartFraction + (fingerX - mRelativeAnchorX) / mScreenWidth
                : fingerX / mScreenWidth;
        fraction = Math.max(0f, Math.min(1f, fraction));
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