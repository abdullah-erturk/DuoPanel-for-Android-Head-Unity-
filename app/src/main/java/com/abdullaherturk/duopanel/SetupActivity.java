package com.abdullaherturk.duopanel;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

public class SetupActivity extends Activity {
    private static abstract class Step {
        int title, desc;
        boolean informational;
        Step(int t, int d) { title = t; desc = d; }
        abstract boolean ok();
        abstract void act(Activity a);
    }

    private final List<Step> steps = new ArrayList<Step>();
    private LinearLayout box;

    @Override
    protected void onCreate(Bundle b) {
        setTheme(UiUtil.theme(this));
        super.onCreate(b);
        setContentView(R.layout.activity_setup);
        box = (LinearLayout) findViewById(R.id.ll_steps);
        steps.add(new Step(R.string.step_overlay, R.string.step_overlay_d) {
            boolean ok() { return PermissionHelper.overlay(SetupActivity.this); }
            void act(Activity a) { PermissionHelper.openOverlay(a); }
        });
        Step restricted = new Step(R.string.step_restricted, R.string.step_restricted_d) {
            boolean ok() { return PermissionHelper.accessibility(SetupActivity.this); }
            void act(Activity a) { PermissionHelper.openAppInfo(a); }
        };
        // Android 13: "Kisitlanmis ayarlara izin ver" secenegi ancak erisilebilirlik once denenip uyari gorulunce cikar
        steps.add(new Step(R.string.step_a11y, R.string.step_a11y_d) {
            boolean ok() { return PermissionHelper.accessibility(SetupActivity.this); }
            void act(Activity a) { PermissionHelper.openAccessibility(a); }
        });
        steps.add(restricted);
        steps.add(new Step(R.string.step_notif, R.string.step_notif_d) {
            boolean ok() { return MusicHub.hasAccess(SetupActivity.this); }
            void act(Activity a) { MusicHub.openAccess(a); }
        });
        steps.add(new Step(R.string.step_location, R.string.step_location_d) {
            boolean ok() { return PermissionHelper.location(SetupActivity.this); }
            void act(Activity a) { PermissionHelper.requestLocation(a); }
        });
        steps.add(new Step(R.string.step_bgloc, R.string.step_bgloc_d) {
            boolean ok() { return PermissionHelper.backgroundLocation(SetupActivity.this); }
            void act(Activity a) { PermissionHelper.openAppInfo(a); }
        });
        steps.add(new Step(R.string.step_battery, R.string.step_battery_d) {
            boolean ok() { return PermissionHelper.battery(SetupActivity.this); }
            void act(Activity a) { PermissionHelper.openBattery(a); }
        });
        findViewById(R.id.btn_done).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        OverlayService.refresh(this);
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) { render(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void render() {
        box.removeAllViews();
        for (final Step s : steps) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_glass);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.bottomMargin = dp(10);
            row.setLayoutParams(rp);

            View dot = new View(this);
            dot.setBackgroundResource(s.ok() ? R.drawable.bg_dot_ok : R.drawable.bg_dot_warn);
            LinearLayout.LayoutParams dpm = new LinearLayout.LayoutParams(dp(10), dp(10));
            dpm.rightMargin = dp(12);
            row.addView(dot, dpm);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView t = new TextView(this);
            t.setText(s.title);
            t.setTextColor(UiUtil.attr(this, R.attr.dpText));
            t.setTextSize(16);
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            TextView d = new TextView(this);
            d.setText(s.desc);
            d.setTextColor(UiUtil.attr(this, R.attr.dpMuted));
            d.setTextSize(12);
            col.addView(t);
            col.addView(d);
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView btn = new TextView(this);
            btn.setText(s.ok() ? R.string.granted : R.string.open);
            btn.setTextColor(UiUtil.attr(this, R.attr.dpAccent));
            btn.setTextSize(13);
            btn.setTypeface(null, android.graphics.Typeface.BOLD);
            btn.setGravity(android.view.Gravity.CENTER);
            btn.setBackgroundResource(R.drawable.bg_pill);
            btn.setMinHeight(dp(44));
            btn.setPadding(dp(18), 0, dp(18), 0);
            btn.setClickable(true);
            btn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { s.act(SetupActivity.this); }
            });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
            bp.leftMargin = dp(12);
            row.addView(btn, bp);
            box.addView(row);
        }
    }
}
