package com.abdullaherturk.duopanel;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class AppList {
    public static final String MAPS = "com.google.android.apps.maps";

    /** Muzik paneli: ikilide bir uygulama gibi secilir, DuoPanel'in kendi paket adiyla saklanir */
    public static boolean isPanel(Context c, String pkg) { return pkg != null && pkg.equals(c.getPackageName()); }

    public static class App {
        public String pkg, label;
    }

    public static List<App> list(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<App> out = new ArrayList<App>();
        List<String> seen = new ArrayList<String>();
        for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
            String p = r.activityInfo.packageName;
            if (p.equals(c.getPackageName()) || seen.contains(p)) continue;
            seen.add(p);
            App a = new App();
            a.pkg = p;
            a.label = String.valueOf(r.loadLabel(pm));
            out.add(a);
        }
        final Collator col = Collator.getInstance();
        Collections.sort(out, new Comparator<App>() {
            public int compare(App x, App y) { return col.compare(x.label, y.label); }
        });
        return out;
    }

    /** Ikili secimi icin: en basta Muzik paneli, sonra uygulamalar */
    public static List<App> listWithPanel(Context c) {
        List<App> out = list(c);
        App m = new App();
        m.pkg = c.getPackageName();
        m.label = c.getString(R.string.music_panel);
        out.add(0, m);
        return out;
    }

    /** Uygulamayi acan intent. Maps + surus modu ayari aciksa hedefsiz surus gorunumu. */
    public static Intent launchIntent(Context c, String pkg) {
        if (isPanel(c, pkg)) return new Intent(c, MusicActivity.class);
        if (MAPS.equals(pkg) && Prefs.mapsDrive(c)) {
            // DriveMode ile ayni istek; once cozumleme kontrolu yapmadan gonder
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:/?free=1&mode=d"));
            i.setPackage(pkg);
            return i;
        }
        return c.getPackageManager().getLaunchIntentForPackage(pkg);
    }

    public static Drawable icon(Context c, String pkg) {
        if (isPanel(c, pkg)) return c.getDrawable(R.drawable.ic_music_app);
        try { return c.getPackageManager().getApplicationIcon(pkg); } catch (Exception e) { return null; }
    }

    public static String label(Context c, String pkg) {
        if (isPanel(c, pkg)) return c.getString(R.string.music_panel);
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) { return pkg; }
    }

    public static boolean installed(Context c, String pkg) {
        try { c.getPackageManager().getApplicationInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }
}
