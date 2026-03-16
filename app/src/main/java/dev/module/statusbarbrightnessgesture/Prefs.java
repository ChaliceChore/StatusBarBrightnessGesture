package dev.module.statusbarbrightnessgesture;

public final class Prefs {
    /** ContentProvider authority — must match AndroidManifest.xml */
    public static final String AUTHORITY           = "dev.module.statusbarbrightnessgesture.prefs";
    /** SharedPreferences file name exposed by RemotePrefProvider */
    public static final String PREF_FILE           = "brightness_gesture_prefs";

    public static final String KEY_GESTURE_ENABLED = "gesture_enabled";
    public static final String KEY_OVERLAY_ENABLED = "overlay_enabled";

    private Prefs() {}
}