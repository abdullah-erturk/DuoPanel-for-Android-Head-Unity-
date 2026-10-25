package com.abdullaherturk.duopanel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Seritteki hava durumuna dokununca: hava durumu konumunu elle sec ya da GPS'e don */
public class WeatherActivity extends Activity {
    private final Handler h = new Handler();
    private LinearLayout results;
    private TextView status;
    private EditText input;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(8), pad, 0);

        TextView cur = new TextView(this);
        String name = Prefs.wxName(this);
        cur.setText(getString(R.string.wx_current, name != null ? name : getString(R.string.wx_auto)));
        cur.setTextSize(13);
        root.addView(cur);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setHint(R.string.wx_hint);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int id, KeyEvent e) { search(); return true; }
        });
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView go = new TextView(this);
        go.setText(R.string.wx_search);
        go.setGravity(Gravity.CENTER);
        go.setTextSize(14);
        go.setTypeface(null, android.graphics.Typeface.BOLD);
        go.setPadding(dp(16), dp(10), dp(16), dp(10));
        go.setClickable(true);
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { search(); }
        });
        row.addView(go);
        root.addView(row);

        status = new TextView(this);
        status.setTextSize(13);
        status.setVisibility(View.GONE);
        root.addView(status);

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(results);
        root.addView(sv, new LinearLayout.LayoutParams(-1, dp(240)));

        AlertDialog d = new AlertDialog.Builder(this, UiUtil.dialogTheme(this))
                .setTitle(R.string.wx_title)
                .setView(root)
                .setNegativeButton(R.string.close, null)
                .setNeutralButton(R.string.wx_auto, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface di, int w) {
                        Prefs.clearWxLocation(WeatherActivity.this);
                        OverlayService.weatherChanged();
                        Toast.makeText(WeatherActivity.this, getString(R.string.wx_saved, getString(R.string.wx_auto)), Toast.LENGTH_SHORT).show();
                    }
                })
                .create();
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface di) { finish(); }
        });
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
        int accent = UiUtil.attr(this, android.R.attr.colorAccent);
        go.setTextColor(accent);
        input.requestFocus();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void search() {
        final String q = input.getText().toString().trim();
        if (q.length() < 2) return;
        results.removeAllViews();
        status.setVisibility(View.GONE);
        new Thread(new Runnable() {
            public void run() {
                HttpURLConnection c = null;
                try {
                    String lang = Locale.getDefault().getLanguage();
                    URL u = new URL("https://geocoding-api.open-meteo.com/v1/search?count=8&format=json&language="
                            + lang + "&name=" + URLEncoder.encode(q, "UTF-8"));
                    c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    final JSONArray arr = new JSONObject(sb.toString()).optJSONArray("results");
                    h.post(new Runnable() {
                        public void run() { show(arr); }
                    });
                } catch (final Exception e) {
                    h.post(new Runnable() {
                        public void run() { status.setText(R.string.wx_error); status.setVisibility(View.VISIBLE); }
                    });
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }).start();
    }

    private void show(JSONArray arr) {
        results.removeAllViews();
        if (arr == null || arr.length() == 0) {
            status.setText(R.string.wx_none);
            status.setVisibility(View.VISIBLE);
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            final String name = o.optString("name");
            final double lat = o.optDouble("latitude"), lon = o.optDouble("longitude");
            StringBuilder label = new StringBuilder(name);
            String a1 = o.optString("admin1", ""), co = o.optString("country", "");
            if (a1.length() > 0 && !a1.equals(name)) label.append(", ").append(a1);
            if (co.length() > 0) label.append(", ").append(co);
            TextView t = new TextView(this);
            t.setText(label);
            t.setTextSize(15);
            t.setPadding(dp(4), dp(12), dp(4), dp(12));
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Prefs.setWxLocation(WeatherActivity.this, name, lat, lon);
                    OverlayService.weatherChanged();
                    Toast.makeText(WeatherActivity.this, getString(R.string.wx_saved, name), Toast.LENGTH_SHORT).show();
                    finish();
                }
            });
            results.addView(t);
        }
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
