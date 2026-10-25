package com.abdullaherturk.duopanel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Muzik paneli ayri surecte calisir; ikiliyi geri getirme istegi ana surece bu alici ile iletilir */
public class PanelReceiver extends BroadcastReceiver {
    public static final String RESUME = "com.abdullaherturk.duopanel.RESUME_PAIR";

    @Override
    public void onReceive(Context c, Intent i) {
        SplitLog.install(c);
        if (RESUME.equals(i.getAction())) SplitController.resumePair(c);
    }
}
