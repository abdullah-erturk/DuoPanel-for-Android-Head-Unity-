package com.abdullaherturk.duopanel;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Gunluk: dosyaya yazilir (uygulamanin tum surecleri ayni dosyayi kullanir; muzik paneli ayri surecte calisir).
 * Boylece cokme / "yanit vermiyor" sonrasi da onceki satirlar kaybolmaz.
 */
public class SplitLog {
    private static final StringBuilder pending = new StringBuilder();
    private static final SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static File file;
    private static String tag = "";

    public static synchronized void add(String s) {
        String line = f.format(new Date()) + "  " + tag + s + "\n";
        if (file == null) { pending.append(line); if (pending.length() > 8000) pending.delete(0, 4000); return; }
        write(line);
    }

    private static void write(String text) {
        try {
            FileOutputStream o = new FileOutputStream(file, true);
            o.write(text.getBytes("UTF-8"));
            o.close();
            if (file.length() > 96 * 1024) trim();
        } catch (Throwable ignored) { }
    }

    private static void trim() {
        try {
            String all = read(48 * 1024);
            FileOutputStream o = new FileOutputStream(file, false);
            o.write(all.getBytes("UTF-8"));
            o.close();
        } catch (Throwable ignored) { }
    }

    private static String read(int max) {
        try {
            RandomAccessFile r = new RandomAccessFile(file, "r");
            long len = r.length(), from = Math.max(0, len - max);
            byte[] b = new byte[(int) (len - from)];
            r.seek(from);
            r.readFully(b);
            r.close();
            String s = new String(b, "UTF-8");
            if (from > 0) { int nl = s.indexOf('\n'); if (nl >= 0) s = s.substring(nl + 1); }
            return s;
        } catch (Throwable e) { return ""; }
    }

    /** Surec basinda bir kez: dosyayi baglar ve cokme yakalayiciyi kurar */
    public static synchronized void install(Context c) {
        if (file != null) return;
        Context app = c.getApplicationContext();
        file = new File(app.getFilesDir(), "duopanel.log");
        String proc = "";
        try {
            int pid = android.os.Process.myPid();
            android.app.ActivityManager am = (android.app.ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            for (android.app.ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) if (p.pid == pid) proc = p.processName;
        } catch (Throwable ignored) { }
        if (proc.endsWith(":panel")) tag = "[panel] ";
        if (pending.length() > 0) { write(pending.toString()); pending.setLength(0); }
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    String st = sw.toString();
                    if (st.length() > 3500) st = st.substring(0, 3500);
                    add("=== COKME [" + t.getName() + "] ===\n" + st);
                } catch (Throwable ignored) { }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    public static synchronized String dump() {
        if (file == null) return pending.length() == 0 ? "-" : pending.toString();
        String s = read(14000);
        return s.length() == 0 ? "-" : s;
    }

    public static synchronized void clear() {
        pending.setLength(0);
        try { if (file != null) new FileOutputStream(file, false).close(); } catch (Throwable ignored) { }
    }
}
