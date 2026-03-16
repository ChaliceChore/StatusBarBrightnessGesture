# Status Bar Brightness Gesture

An LSPosed module that lets you swipe horizontally on the status bar to control screen brightness — works with the notification shade open or closed, and on the lockscreen.

## Requirements

- Android 12 or higher
- [Magisk](https://github.com/topjohnwu/Magisk) (rooted device)
- [LSPosed](https://github.com/LSPosed/LSPosed) (Zygisk edition recommended)

## Installation

1. Install the APK from the [Releases](../../releases) page
2. Open LSPosed → Modules → enable **Status Bar Brightness Gesture**
3. Set the scope to **System UI**
4. Reboot
5. Open the app and configure your preferences

### One-time ADB setup (required for toggle persistence across reboots)

Connect your device via ADB and run:
```
adb shell pm grant dev.module.statusbarbrightnessgesture android.permission.WRITE_SECURE_SETTINGS
```
This only needs to be run once after a fresh install. It survives reboots and app updates.

## Usage

- **Swipe right** on the status bar to increase brightness
- **Swipe left** on the status bar to decrease brightness
- Works with the notification shade open or closed
- Works on the lockscreen
- The brightness indicator follows your wallpaper accent colour

## Settings

Open the app to configure:
- **Enable gesture** — turn the swipe gesture on or off
- **Show brightness indicator** — show or hide the brightness % overlay while swiping

## Compatibility

Works on most AOSP-based Android 12+ ROMs including:
- Pixel stock (GrapheneOS, CalyxOS)
- LineageOS and derivatives (crDroid, EvolutionX, DerpFest, etc.)

May not work on heavily customised ROMs such as Samsung OneUI or Xiaomi HyperOS, as these replace the standard status bar classes.

## Tested on

- Pixel 6 (Oriole), DerpFest 16.2, Android 16, LSPosed v1.11.0

## License

MIT
```

---

## Step 5 — Initialize Git and make your first commit

Open a command prompt in your project root:
```
cd C:\Users\matthew\AndroidStudioProjects\StatusBarBrightnessGesture

git init
git add .
git commit -m "v1.6.0 - toggle persistence, Material You UI, broadcast prefs"
git branch -M main
git remote add origin https://github.com/yourusername/StatusBarBrightnessGesture.git
git push -u origin main
```

---

## Step 6 — Add each version as a GitHub Release

You can't recreate the git history for v1.0.0 through v1.5.0 unless you still have those APKs. Check your Android Studio build outputs folder — you may still have some of the old debug APKs at:
```
C:\Users\matthew\AndroidStudioProjects\StatusBarBrightnessGesture\app\build\outputs\apk\debug\
```

For each APK you still have, create a release on GitHub:

1. Go to your repository → **Releases** → **Draft a new release**
2. Click **Choose a tag** → type the version (e.g. `v1.0.0`) → **Create new tag**
3. Set the **Release title** to match, e.g. `v1.0.0`
4. Add release notes (see below)
5. Attach the APK file
6. Click **Publish release**

**Release notes by version:**

| Version | Notes |
|---------|-------|
| v1.0.0 | Initial release — horizontal swipe gesture on status bar |
| v1.1.0 | Brightness range matched to QS slider |
| v1.2.0 | Brightness % overlay indicator added |
| v1.3.0 | Gesture works with shade open and closed, lockscreen support |
| v1.4.0 | Toggle persistence via broadcast receiver — gesture and overlay toggles work immediately |
| v1.5.0 | Material You dynamic colours, dark/light mode support, status bar inset fix |
| v1.6.0 | Toggle state persists across reboots via Settings.Secure — no app open required after boot |

For versions where you no longer have the APK, you can still create the release tag without attaching a file — it documents the history even without the binary.

---

## Step 7 — Future workflow

Every time you reach a new restore point going forward:
```
git add .
git commit -m "v1.7.0 - description of what changed"
git push