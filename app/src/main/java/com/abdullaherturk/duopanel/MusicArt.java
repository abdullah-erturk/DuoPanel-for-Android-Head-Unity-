package com.abdullaherturk.duopanel;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Kapak yukleme, bulaniklastirma ve kapaktan vurgu rengi */
public class MusicArt {
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService pool = Executors.newFixedThreadPool(2);
    private static final Handler main = new Handler(Looper.getMainLooper());

    public interface Done { void done(Bitmap b); }

    /** Sarkinin kapagi: once oturumdaki resim, yoksa adresinden yuklenir */
    public static Bitmap metadataBitmap(MediaMetadata m) {
        if (m == null) return null;
        for (String k : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            try { Bitmap b = m.getBitmap(k); if (b != null) return b; } catch (Exception e) { }
        }
        return null;
    }

    public static Uri metadataUri(MediaMetadata m) {
        if (m == null) return null;
        for (String k : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
            String s = m.getString(k);
            if (s != null && s.length() > 0) return Uri.parse(s);
        }
        return null;
    }

    /** Adresten resim yukler (content, android.resource, file, http/https); sonuc ana is parcaciginda */
    public static void load(final Context c, final Uri u, final int maxPx, final Done d) {
        if (u == null) { d.done(null); return; }
        final String key = u.toString() + "#" + maxPx;
        Bitmap hit = cache.get(key);
        if (hit != null) { d.done(hit); return; }
        pool.execute(new Runnable() {
            public void run() {
                Bitmap b = null;
                InputStream in = null;
                try {
                    String sc = u.getScheme();
                    if ("http".equals(sc) || "https".equals(sc)) {
                        HttpURLConnection con = (HttpURLConnection) new URL(u.toString()).openConnection();
                        con.setConnectTimeout(6000);
                        con.setReadTimeout(8000);
                        in = con.getInputStream();
                    } else {
                        in = c.getContentResolver().openInputStream(u);
                    }
                    byte[] data = readAll(in);
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, o);
                    int s = 1;
                    while (o.outWidth / (s * 2) >= maxPx && o.outHeight / (s * 2) >= maxPx) s *= 2;
                    o = new BitmapFactory.Options();
                    o.inSampleSize = s;
                    b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                } catch (Throwable e) {
                    b = null;
                } finally {
                    try { if (in != null) in.close(); } catch (Exception e) { }
                }
                final Bitmap res = b;
                if (res != null) cache.put(key, res);
                main.post(new Runnable() { public void run() { d.done(res); } });
            }
        });
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > 12 * 1024 * 1024) break;
        }
        return bo.toByteArray();
    }

    /** Listede kucuk resim: once bitmap, yoksa adres; gec gelen sonuc yanlis satira yazilmasin diye etiketlenir */
    public static void into(Context c, final ImageView iv, Bitmap bm, Uri u, int px, final int placeholder) {
        iv.setTag(u);
        if (bm != null) { iv.clearColorFilter(); iv.setPadding(0, 0, 0, 0); iv.setImageBitmap(bm); return; }
        iv.setImageResource(placeholder);
        if (u == null) return;
        final Uri want = u;
        load(c, u, px, new Done() {
            public void done(Bitmap b) {
                if (b != null && want.equals(iv.getTag())) { iv.clearColorFilter(); iv.setPadding(0, 0, 0, 0); iv.setImageBitmap(b); }
            }
        });
    }

    private static Bitmap soft(Bitmap src) {
        try {
            if (Build23.isHardware(src)) return src.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable e) { }
        return src;
    }

    /** Arka plan icin bulanik kucuk kopya; ImageView buyuturken yumusatir */
    public static Bitmap blur(Bitmap src) {
        if (src == null) return null;
        try {
            src = soft(src);
            int w = 40;
            int h = Math.max(1, Math.min(80, Math.round(40f * src.getHeight() / Math.max(1, src.getWidth()))));
            Bitmap s = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true);
            int[] px = new int[w * h];
            s.getPixels(px, 0, w, 0, 0, w, h);
            for (int pass = 0; pass < 3; pass++) {
                boxH(px, w, h, 3);
                boxV(px, w, h, 3);
            }
            s.setPixels(px, 0, w, 0, 0, w, h);
            return s;
        } catch (Throwable e) { return null; }
    }

    private static void boxH(int[] p, int w, int h, int r) {
        int[] out = new int[w];
        for (int y = 0; y < h; y++) {
            int off = y * w;
            for (int x = 0; x < w; x++) {
                int a = 0, rr = 0, g = 0, b = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int xx = Math.min(w - 1, Math.max(0, x + k));
                    int c = p[off + xx];
                    a += c >>> 24; rr += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++;
                }
                out[x] = ((a / n) << 24) | ((rr / n) << 16) | ((g / n) << 8) | (b / n);
            }
            System.arraycopy(out, 0, p, off, w);
        }
    }

    private static void boxV(int[] p, int w, int h, int r) {
        int[] out = new int[h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int a = 0, rr = 0, g = 0, b = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int yy = Math.min(h - 1, Math.max(0, y + k));
                    int c = p[yy * w + x];
                    a += c >>> 24; rr += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++;
                }
                out[y] = ((a / n) << 24) | ((rr / n) << 16) | ((g / n) << 8) | (b / n);
            }
            for (int y = 0; y < h; y++) p[y * w + x] = out[y];
        }
    }

    /** Kapaktaki en canli renk; temaya gore okunur tona getirilir. Bulunamazsa 0 */
    public static int accent(Bitmap src, boolean light) {
        if (src == null) return 0;
        try {
            src = soft(src);
            Bitmap s = Bitmap.createScaledBitmap(src, 24, 24, true);
            float best = 0;
            float[] hsvBest = null;
            float[] hsv = new float[3];
            for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) {
                Color.colorToHSV(s.getPixel(x, y), hsv);
                if (hsv[2] < 0.25f) continue;
                float score = hsv[1] * (0.4f + hsv[2]);
                if (score > best) { best = score; hsvBest = hsv.clone(); }
            }
            if (hsvBest == null || hsvBest[1] < 0.18f) return 0;
            if (light) { hsvBest[1] = clamp(hsvBest[1], 0.5f, 0.9f); hsvBest[2] = 0.52f; }
            else { hsvBest[1] = clamp(hsvBest[1], 0.35f, 0.7f); hsvBest[2] = 0.95f; }
            return Color.HSVToColor(hsvBest);
        } catch (Throwable e) { return 0; }
    }

    private static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }

    /** API 26+ donanim bitmap kontrolu (derleme API 23 ile yapildigi icin yansima) */
    static class Build23 {
        static boolean isHardware(Bitmap b) {
            if (android.os.Build.VERSION.SDK_INT < 26) return false;
            Bitmap.Config c = b.getConfig();
            return c != null && "HARDWARE".equals(c.name());
        }
    }
}
