package dev.module.statusbarbrightnessgesture;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

@SuppressWarnings("deprecation")
public class SettingsActivity extends Activity {

    private SharedPreferences mPrefs;
    private int colText;
    private int colTextSecondary;
    private int colSurface;
    private int colBackground;
    private int colDivider;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Apply Material You dynamic colours before super.onCreate sets the theme
        try {
            Class<?> dc = Class.forName("com.google.android.material.color.DynamicColors");
            dc.getMethod("applyToActivityIfAvailable", Activity.class).invoke(null, this);
        } catch (Throwable ignored) {}

        super.onCreate(savedInstanceState);
        resolveColours();

        mPrefs = getSharedPreferences("brightness_gesture_prefs", MODE_PRIVATE);

        float dp = getResources().getDisplayMetrics().density;
        int hPad = (int)(24*dp), vPad = (int)(20*dp);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(colBackground);
        setContentView(scroll);

        // Push content below the status bar using window insets
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                    v.getPaddingLeft(),
                    insets.getSystemWindowInsetTop(),
                    v.getPaddingRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);

        // ── Header ────────────────────────────────────────────────────────────
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(colSurface);
        header.setPadding(hPad, vPad, hPad, vPad);

        TextView title = new TextView(this);
        title.setText("Status Bar Brightness");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(colText);
        header.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Swipe horizontally on the status bar to adjust brightness");
        subtitle.setTextSize(14);
        subtitle.setTextColor(colTextSecondary);
        subtitle.setPadding(0, (int)(6*dp), 0, 0);
        header.addView(subtitle);

        root.addView(header, matchWidth());
        root.addView(divider(dp), matchWidth());

        // ── Toggles ───────────────────────────────────────────────────────────
        buildToggleRow(root, "Enable gesture",
                "Swipe left to dim, right to brighten",
                Prefs.KEY_GESTURE_ENABLED, true, dp, hPad, vPad);
        root.addView(divider(dp), matchWidth());

        buildToggleRow(root, "Show brightness indicator",
                "Displays brightness % while swiping",
                Prefs.KEY_OVERLAY_ENABLED, true, dp, hPad, vPad);
        root.addView(divider(dp), matchWidth());

        // ── How to use ────────────────────────────────────────────────────────
        LinearLayout hint = new LinearLayout(this);
        hint.setOrientation(LinearLayout.VERTICAL);
        hint.setBackgroundColor(colSurface);
        hint.setPadding(hPad, vPad, hPad, vPad);

        TextView hintTitle = new TextView(this);
        hintTitle.setText("How to use");
        hintTitle.setTextSize(14);
        hintTitle.setTypeface(Typeface.DEFAULT_BOLD);
        hintTitle.setTextColor(colText);
        hint.addView(hintTitle);

        for (String tip : new String[]{
                "• Swipe right on the status bar to increase brightness",
                "• Swipe left to decrease brightness",
                "• Works with notification shade open or closed",
                "• Works on the lockscreen",
                "• The % indicator matches the system brightness display",
                "• Indicator colour follows your wallpaper accent"}) {
            TextView t = new TextView(this);
            t.setText(tip);
            t.setTextSize(13);
            t.setTextColor(colTextSecondary);
            t.setPadding(0, (int)(5*dp), 0, 0);
            hint.addView(t);
        }
        root.addView(hint, matchWidth());

        TextView note = new TextView(this);
        note.setText("Changes take effect immediately — no reboot needed.");
        note.setTextSize(12);
        note.setTextColor(colTextSecondary);
        note.setGravity(Gravity.CENTER);
        note.setPadding(hPad, (int)(16*dp), hPad, (int)(16*dp));
        root.addView(note, matchWidth());
    }

    private void resolveColours() {
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        TypedValue tv = new TypedValue();

        // colorBackground
        if (getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true)
                && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            colBackground = tv.data;
        } else {
            colBackground = night ? 0xFF1C1B1F : 0xFFFFFBFE;
        }

        // colorSurface — try Material3 attr name
        int surfaceAttr = getResources().getIdentifier(
                "colorSurface", "attr", getPackageName());
        if (surfaceAttr == 0) {
            // Fall back to window background which Material3 sets correctly
            if (getTheme().resolveAttribute(android.R.attr.windowBackground, tv, true)
                    && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                colSurface = tv.data;
            } else {
                colSurface = colBackground;
            }
        } else {
            if (getTheme().resolveAttribute(surfaceAttr, tv, true)) {
                colSurface = tv.data;
            } else {
                colSurface = colBackground;
            }
        }

        // textColorPrimary
        if (getTheme().resolveAttribute(android.R.attr.textColorPrimary, tv, true)
                && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            colText = tv.data;
        } else {
            colText = night ? 0xFFE6E1E5 : 0xFF1C1B1F;
        }

        // textColorSecondary
        if (getTheme().resolveAttribute(android.R.attr.textColorSecondary, tv, true)
                && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            colTextSecondary = tv.data;
        } else {
            colTextSecondary = night ? 0xFFCAC4D0 : 0xFF49454F;
        }

        // divider — subtle outline
        colDivider = night ? 0x1FFFFFFF : 0x1F000000;
    }

    private void buildToggleRow(LinearLayout root, String titleText, String descText,
                                String prefKey, boolean defaultVal,
                                float dp, int hPad, int vPad) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(colSurface);
        row.setPadding(hPad, vPad, hPad, vPad);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tv = new TextView(this);
        tv.setText(titleText);
        tv.setTextSize(16);
        tv.setTextColor(colText);
        textCol.addView(tv);

        TextView dv = new TextView(this);
        dv.setText(descText);
        dv.setTextSize(13);
        dv.setTextColor(colTextSecondary);
        dv.setPadding(0, (int)(3*dp), 0, 0);
        textCol.addView(dv);

        row.addView(textCol);

        Switch sw = new Switch(this);
        sw.setChecked(mPrefs.getBoolean(prefKey, defaultVal));
        sw.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            mPrefs.edit().putBoolean(prefKey, checked).apply();
            sendPrefs();
        });
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());

        root.addView(row, matchWidth());
    }

    private void sendPrefs() {
        Intent intent = new Intent(Prefs.ACTION_PREFS_CHANGED);
        intent.setPackage("com.android.systemui");
        intent.putExtra(Prefs.KEY_GESTURE_ENABLED,
                mPrefs.getBoolean(Prefs.KEY_GESTURE_ENABLED, true));
        intent.putExtra(Prefs.KEY_OVERLAY_ENABLED,
                mPrefs.getBoolean(Prefs.KEY_OVERLAY_ENABLED, true));
        sendBroadcast(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sendPrefs();
    }

    private View divider(float dp) {
        View v = new View(this);
        v.setBackgroundColor(colDivider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int)(1*dp));
        lp.setMarginStart((int)(24*dp));
        v.setLayoutParams(lp);
        return v;
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}