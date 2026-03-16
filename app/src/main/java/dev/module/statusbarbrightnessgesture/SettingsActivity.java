package dev.module.statusbarbrightnessgesture;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = getSharedPreferences(Prefs.KEY_GESTURE_ENABLED + "_prefs", MODE_PRIVATE);

        float dp = getResources().getDisplayMetrics().density;
        int hPad = (int)(24*dp), vPad = (int)(20*dp);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#F2F2F2"));
        setContentView(scroll);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(Color.WHITE);
        header.setPadding(hPad, vPad, hPad, vPad);

        TextView title = new TextView(this);
        title.setText("Status Bar Brightness");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#1A1A1A"));
        header.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Swipe horizontally on the status bar to adjust brightness");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Color.parseColor("#666666"));
        subtitle.setPadding(0, (int)(6*dp), 0, 0);
        header.addView(subtitle);

        root.addView(header, matchWidth());
        root.addView(divider(dp), matchWidth());

        buildToggleRow(root, "Enable gesture",
                "Swipe left to dim, right to brighten",
                Prefs.KEY_GESTURE_ENABLED, true, dp, hPad, vPad);
        root.addView(divider(dp), matchWidth());

        buildToggleRow(root, "Show brightness indicator",
                "Displays brightness % while swiping",
                Prefs.KEY_OVERLAY_ENABLED, true, dp, hPad, vPad);
        root.addView(divider(dp), matchWidth());

        LinearLayout hint = new LinearLayout(this);
        hint.setOrientation(LinearLayout.VERTICAL);
        hint.setBackgroundColor(Color.WHITE);
        hint.setPadding(hPad, vPad, hPad, vPad);

        TextView hintTitle = new TextView(this);
        hintTitle.setText("How to use");
        hintTitle.setTextSize(14);
        hintTitle.setTypeface(Typeface.DEFAULT_BOLD);
        hintTitle.setTextColor(Color.parseColor("#1A1A1A"));
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
            t.setTextColor(Color.parseColor("#555555"));
            t.setPadding(0, (int)(5*dp), 0, 0);
            hint.addView(t);
        }
        root.addView(hint, matchWidth());

        TextView note = new TextView(this);
        note.setText("Changes take effect immediately — no reboot needed.");
        note.setTextSize(12);
        note.setTextColor(Color.parseColor("#999999"));
        note.setGravity(Gravity.CENTER);
        note.setPadding(hPad, (int)(16*dp), hPad, (int)(16*dp));
        root.addView(note, matchWidth());
    }

    private void buildToggleRow(LinearLayout root, String titleText, String descText,
                                String prefKey, boolean defaultVal,
                                float dp, int hPad, int vPad) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(Color.WHITE);
        row.setPadding(hPad, vPad, hPad, vPad);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tv = new TextView(this);
        tv.setText(titleText);
        tv.setTextSize(16);
        tv.setTextColor(Color.parseColor("#1A1A1A"));
        textCol.addView(tv);

        TextView dv = new TextView(this);
        dv.setText(descText);
        dv.setTextSize(13);
        dv.setTextColor(Color.parseColor("#888888"));
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
        sendPrefs(); // re-sync on every resume in case SystemUI restarted
    }

    private View divider(float dp) {
        View v = new View(this);
        v.setBackgroundColor(Color.parseColor("#E0E0E0"));
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