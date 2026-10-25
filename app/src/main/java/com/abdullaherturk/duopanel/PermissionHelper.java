package com.abdullaherturk.duopanel;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

public class PermissionHelper {
    static final String BG_LOC = "android.permission.ACCESS_BACKGROUND_LOCATION";

    public static boolean overlay(Context c) { return Settings.canDrawOverlays(c); }

    public static boolean accessibility(Context c) {
        if (SplitService.get() != null) return true;
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) return false;
            String full = new ComponentName(c, SplitService.class).flattenToString();
            String shortName = c.getPackageName() + "/.SplitService";
            for (String p : s.split(":")) if (p.equalsIgnoreCase(full) || p.equalsIgnoreCase(shortName)) return true;
        } catch (Exception e) { }
        return false;
    }

    /**
     * Erisilebilirlik hizmeti kapandiysa (or. Tbox "Tumunu temizle" uygulamayi zorla durdurunca)
     * adb ile WRITE_SECURE_SETTINGS verildiyse hizmeti kendisi yeniden acar.
     */
    public static boolean tryEnableAccessibility(Context c) {
        if (accessibility(c)) return false;
        if (c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return false;
        try {
            String full = new ComponentName(c, SplitService.class).flattenToString();
            String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            StringBuilder sb = new StringBuilder();
            if (s != null) for (String p : s.split(":")) {
                if (p.length() == 0 || p.equalsIgnoreCase(full) || p.equalsIgnoreCase(c.getPackageName() + "/.SplitService")) continue;
                if (sb.length() > 0) sb.append(':');
                sb.append(p);
            }
            if (sb.length() > 0) sb.append(':');
            sb.append(full);
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, sb.toString());
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            SplitLog.add("accessibility re-enabled by DuoPanel");
            return true;
        } catch (Exception e) {
            SplitLog.add("accessibility re-enable failed: " + e);
            return false;
        }
    }

    public static boolean location(Context c) {
        return c.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean backgroundLocation(Context c) {
        return c.checkSelfPermission(BG_LOC) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean battery(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    public static boolean allCore(Context c) { return overlay(c) && accessibility(c); }

    private static boolean tryStart(Context c, Intent i) {
        try {
            if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) { return false; }
    }

    public static void openAppInfo(Context c) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
        if (!tryStart(c, i)) Toast.makeText(c, R.string.settings_missing, Toast.LENGTH_LONG).show();
    }

    public static void openOverlay(Context c) {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + c.getPackageName()));
        if (!tryStart(c, i) && !tryStart(c, new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))) openAppInfo(c);
    }

    public static void openAccessibility(Context c) {
        if (!tryStart(c, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) openAppInfo(c);
    }

    public static void requestLocation(Activity a) {
        if (!location(a)) {
            a.requestPermissions(new String[]{"android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"}, 11);
        } else {
            openAppInfo(a); // "Her zaman izin ver" secenegi uygulama izinlerinde
        }
    }

    public static void openBattery(Context c) {
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.getPackageName()));
        if (!tryStart(c, i) && !tryStart(c, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) openAppInfo(c);
    }
}
