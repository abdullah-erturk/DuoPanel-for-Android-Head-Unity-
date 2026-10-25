package com.abdullaherturk.duopanel;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/** Ana ekrandaki kayitli ikili kisayolu: iki uygulamayi secer ve bolunmus ekranda acar */
public class ShortcutActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String l = getIntent().getStringExtra("l");
        String r = getIntent().getStringExtra("r");
        if (l != null && r != null && AppList.installed(this, l) && AppList.installed(this, r)) {
            Prefs.setSide(this, Prefs.LEFT, l);
            Prefs.setSide(this, Prefs.RIGHT, r);
            SplitLog.add("shortcut: " + l + " + " + r);
            OverlayService.refresh(getApplicationContext());
            SplitController.launchPair(getApplicationContext());
        } else {
            Toast.makeText(this, R.string.pick_both, Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
