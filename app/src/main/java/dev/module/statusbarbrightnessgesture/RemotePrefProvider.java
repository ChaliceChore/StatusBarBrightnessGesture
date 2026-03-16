package dev.module.statusbarbrightnessgesture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * ContentProvider that exposes our SharedPreferences to SystemUI's process.
 *
 * SystemUI calls getBoolean(key) via call(), which returns a Bundle containing
 * the value. No external library needed.
 *
 * URI format: content://dev.module.statusbarbrightnessgesture.prefs/
 * Call method: "get"
 * Call arg:    the preference key
 * Call extras: optional Bundle with "default" boolean
 */
public class RemotePrefProvider extends ContentProvider {

    public static final String METHOD_GET = "get";
    public static final String EXTRA_VALUE = "value";
    public static final String EXTRA_DEFAULT = "default";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_GET.equals(method) || arg == null) return null;
        SharedPreferences prefs = getContext()
                .getSharedPreferences(Prefs.PREF_FILE, android.content.Context.MODE_PRIVATE);
        Bundle result = new Bundle();
        boolean defaultVal = extras != null && extras.getBoolean(EXTRA_DEFAULT, true);
        result.putBoolean(EXTRA_VALUE, prefs.getBoolean(arg, defaultVal));
        return result;
    }

    // ── Unused ContentProvider methods ───────────────────────────────────────

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String sel, String[] args) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}