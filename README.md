# Status Bar Brightness Gesture — LSPosed Module

Swipe horizontally across the **status bar** to adjust screen brightness in real time.
Replicates the crDroid feature on any AOSP-derived ROM (tested target: DerpFest 16.2 on Pixel 6 / Oriole).

---

## How it works

| Gesture | Result |
|---|---|
| Swipe right across status bar | Increase brightness |
| Swipe left across status bar | Decrease brightness |
| Release finger | Commit brightness to system |

The gesture mirrors the exact call sequence used by the Quick Settings brightness slider:
- `DisplayManager.setTemporaryBrightness()` for live updates while swiping
- `DisplayManager.setBrightness()` to commit on finger lift

A gamma correction (γ ≈ 2.2) is applied so the gesture feels perceptually linear, matching
the curve used by the QS slider internally.

---

## Prerequisites

- **Rooted device** with [Magisk](https://github.com/topjohnwu/Magisk)
- **LSPosed** installed (Zygisk edition recommended)
  - [LSPosed GitHub](https://github.com/LSPosed/LSPosed)
- **Android Studio** (Hedgehog or newer) for building
- **JDK 17** (bundled with Android Studio)

---

## Building

1. Clone or extract this project
2. Open the root folder in Android Studio
3. Wait for Gradle sync to complete
4. **Build → Generate Signed Bundle / APK → APK → debug** (for testing)
   - Or run via the green ▶ button with a connected device/emulator
5. The APK will be at:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

> **Note:** The `xposed-api` dependency is `compileOnly` — it is used for compilation only
> and is NOT packaged in the APK. LSPosed injects its own XposedBridge at runtime.

---

## Installation

```bash
# Install the APK (module must be installed as a regular app first)
adb install app/build/outputs/apk/debug/app-debug.apk
```

Then in the **LSPosed Manager** app:
1. Open **Modules**
2. Find **Status Bar Brightness Gesture**
3. Enable it
4. Set the scope to **System UI** (`com.android.systemui`)
5. Reboot (or soft-reboot SystemUI via LSPosed Manager if available)

---

## Gesture details

- The gesture only fires if the touch **starts within the top ~6% of the screen** (status bar region)
- A touch is only recognised as a brightness gesture if it moves **horizontally more than 2× its vertical movement** — this prevents conflicts with shade-pull gestures
- A minimum movement threshold (based on `ViewConfiguration.scaledTouchSlop`) prevents accidental triggering on taps
- The gesture is **additive**: swiping from the current brightness level, not always from 0

---

## Compatibility

| Android version | Status |
|---|---|
| Android 13 (API 33) | ✅ Supported |
| Android 14 (API 34) | ✅ Supported |
| Android 15 (API 35) | ✅ Supported |
| Android 16 (API 36) | ✅ Supported (primary target) |

Hook target `NotificationShadeWindowView.dispatchTouchEvent()` is present and stable
in AOSP since Android 11. Both crDroid and DerpFest source trees were verified.

---

## Troubleshooting

**Gesture does nothing:**
- Confirm LSPosed scope includes `com.android.systemui`
- Check LSPosed logs for `BrightnessGestureHook` tag
- Run `adb logcat -s BrightnessGestureHook` while swiping and check for error messages

**Shade opens instead of brightness changing:**
- The gesture detection threshold may need tuning for your swipe speed. The horizontal-to-vertical
  ratio check (`HORIZONTAL_DIRECTION_RATIO = 2.0`) can be lowered in `BrightnessGestureHook.java`
  if needed

**Brightness jumps instead of feeling smooth:**
- Verify `getCurrentBrightness()` is returning a valid value at gesture start (check logcat)
- The `GAMMA` constant (2.2) can be adjusted for a different feel

---

## Architecture notes

- **Hook point:** `NotificationShadeWindowView.dispatchTouchEvent(MotionEvent)`
  - Chosen over `ShadeViewController.handleExternalTouch()` for stability across ROM variants
- **Brightness APIs:** Both `setTemporaryBrightness` and `setBrightness` are `@hide` methods
  accessed via reflection. They are called on the main thread (temporary) and a background
  thread via `AsyncTask.execute()` (commit), matching SystemUI's own pattern exactly.
- **No Settings write during swipe:** `setTemporaryBrightness` bypasses Settings, ensuring
  smooth updates without triggering ContentObserver callbacks that could cause feedback loops.
