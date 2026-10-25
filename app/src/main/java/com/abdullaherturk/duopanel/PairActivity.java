package com.abdullaherturk.duopanel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;

/**
 * Bolunmus ekran icin tam ekran ara ekran.
 * Android 12L+ : tam ekrandaki bir activity FLAG_ACTIVITY_LAUNCH_ADJACENT ile uygulama acarsa
 * sistem ekrani boler. Ardindan ayni taraftan diger uygulama acilir ve bu ekran kapanir.
 */
public class PairActivity extends Activity {
    private static final int FLAG_LAUNCH_ADJACENT = 0x00001000;
    private static long lastToken;
    private final Handler h = new Handler();

    public static void launch(Context c, String left, String right) { launch(c, left, right, false); }

    /** keep: uygulamalar sadece one getirilir (surus istegi yok, navigasyon/oynatma kesilmez) */
    public static void launch(Context c, String left, String right, boolean keep) {
        Intent i = new Intent(c, PairActivity.class)
                .putExtra("l", left).putExtra("r", right).putExtra("k", keep).putExtra("t", SystemClock.elapsedRealtime())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        c.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle b) {
        setTheme(UiUtil.theme(this));
        super.onCreate(b);
        View v = new View(this);
        v.setBackgroundColor(UiUtil.attr(this, R.attr.dpBg));
        setContentView(v);
        // ekran bolununce sistem bu ekrani yeniden olusturabilir: ikinci kez calistirma
        long token = getIntent().getLongExtra("t", 0);
        if (b != null || token == lastToken) { SplitLog.add("pair activity re-created, skipped"); return; }
        lastToken = token;
        final String l = getIntent().getStringExtra("l");
        final String r = getIntent().getStringExtra("r");
        if (l == null || r == null) { finish(); return; }
        // eski muzik paneli gorevi tam ekran one gelip Haritalar'i ezmesin: once kapat, taze acilsin
        boolean swap = Prefs.get(this, "swap", false);
        final String adjacent = swap ? l : r;
        final String same = swap ? r : l;
        SplitLog.add("pair activity: adjacent=" + adjacent + " same=" + same);
        h.postDelayed(new Runnable() {
            public void run() { open(adjacent, FLAG_LAUNCH_ADJACENT); }
        }, 400);
        h.postDelayed(new Runnable() {
            public void run() { open(same, 0); }
        }, 1800);
        h.postDelayed(new Runnable() {
            public void run() {
                finish();
                overridePendingTransition(0, 0);
                SplitService s = SplitService.get();
                if (s != null) s.afterPair(l, r);
            }
        }, 2700);
    }

    static void closePanelTask(Context c) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(ACTIVITY_SERVICE);
            for (android.app.ActivityManager.AppTask t : am.getAppTasks()) {
                android.content.ComponentName cn = null;
                try { cn = t.getTaskInfo().baseIntent.getComponent(); } catch (Exception ignored) {}
                if (cn != null && cn.getClassName().endsWith(".MusicActivity")) {
                    t.finishAndRemoveTask();
                    SplitLog.add("old panel task closed");
                }
            }
        } catch (Exception e) { SplitLog.add("panel task close failed: " + e); }
    }

    private void open(String pkg, int flags) {
        try {
            boolean keep = getIntent().getBooleanExtra("k", false);
            // durumu koru: Haritalar hedefsiz surusteyse surus istegiyle getir (yoksa ana ekranina doner); navigasyondaysa sadece one getir
            SplitService ss = SplitService.get();
            boolean freeDrive = AppList.MAPS.equals(pkg) && ss != null && ss.mapsInFreeDrive();
            Intent i = (keep && !freeDrive && !AppList.isPanel(this, pkg))
                    ? getPackageManager().getLaunchIntentForPackage(pkg)
                    : AppList.launchIntent(this, pkg);
            if (i == null) { SplitLog.add("no launch intent: " + pkg); return; }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | flags);
            try { startActivity(i); }
            catch (android.content.ActivityNotFoundException nf) {
                SplitLog.add("drive intent rejected, normal launch: " + pkg);
                i = AppList.isPanel(this, pkg) ? AppList.launchIntent(this, pkg) : getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) return;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | flags);
                startActivity(i);
            }
            SplitLog.add("pair open " + pkg + (flags != 0 ? " (adjacent)" : "") + (i.getData() != null ? " " + i.getData() : ""));
        } catch (Exception e) { SplitLog.add("pair open failed " + pkg + ": " + e); }
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
