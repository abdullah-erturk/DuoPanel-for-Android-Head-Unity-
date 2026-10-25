package com.abdullaherturk.duopanel;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

public class UiUtil {
    public static int attr(Context c, int a) {
        TypedValue v = new TypedValue();
        c.getTheme().resolveAttribute(a, v, true);
        return v.data;
    }

    public static boolean isLight(Context c) {
        int m = Prefs.themeMode(c);
        if (m == 1) return true;
        if (m == 2) return false;
        int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night != Configuration.UI_MODE_NIGHT_YES;
    }

    public static int theme(Context c) {
        return isLight(c) ? R.style.Theme_DuoPanel_Light : R.style.Theme_DuoPanel;
    }

    public static int dialogTheme(Context c) {
        return isLight(c) ? R.style.Theme_DuoPanel_Light_Dialog : R.style.Theme_DuoPanel_Dialog;
    }

    /** Gecmis simgesi; silme modunda kirmizi x rozeti ile */
    public static View histItem(Context c, Drawable icon, int sizePx, boolean del) {
        float d = c.getResources().getDisplayMetrics().density;
        FrameLayout f = new FrameLayout(c);
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(icon);
        f.addView(iv, new FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER));
        if (del) {
            iv.setAlpha(0.55f);
            TextView x = new TextView(c);
            x.setText("\u00D7");
            x.setTextColor(0xFFFFFFFF);
            x.setTextSize(14);
            x.setTypeface(null, Typeface.BOLD);
            x.setGravity(Gravity.CENTER);
            x.setIncludeFontPadding(false);
            x.setBackgroundResource(R.drawable.bg_badge_del);
            int b = Math.round(20 * d);
            f.addView(x, new FrameLayout.LayoutParams(b, b, Gravity.TOP | Gravity.END));
        }
        return f;
    }
}
