package com.abdullaherturk.duopanel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        SplitLog.add("boot: " + (i == null ? "" : i.getAction()));
        if (Prefs.autostart(c)) OverlayService.start(c);
        // Pair acilisi SplitService baglaninca yapilir (erisilebilirlik hizmeti acilista sistem tarafindan baslatilir)
    }
}
