package com.abdullaherturk.duopanel;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Muzik paneli icin calan uygulamanin oturumu, kontroller, calma sirasi ve kitaplik.
 * Yalnizca Android'in acik medya API'leri kullanilir (bildirim erisimi + MediaBrowser).
 */
public class MusicHub {
    public interface Listener {
        void onMusicChanged();      // sarki, kapak, durum ya da kaynak degisti
        void onSectionsChanged();   // kitaplik bolumleri ya da secili bolumun listesi degisti
    }

    public static final String QUEUE = "@queue";
    private static final String BROWSE_ACTION = "android.media.browse.MediaBrowserService";
    private static final String EXTRA_RECENT = "android.service.media.extra.RECENT";

    public static class Section {
        public String id, title;
        public boolean pinned;
        /** sabitlenmis bolum: once yuklenmesi gereken ust klasorler (uygulama alt listeyi ancak bunlardan sonra verir) */
        public List<String> chain = new ArrayList<String>();
        Section(String i, String t) { id = i; title = t; }
    }
    public static class Song {
        public String id, title, sub;
        public long queueId = -1;
        public boolean folder;
        public android.graphics.Bitmap icon;
        public android.net.Uri iconUri;
    }

    private static MusicHub inst;
    public static MusicHub get(Context c) {
        if (inst == null) inst = new MusicHub(c.getApplicationContext());
        return inst;
    }

    private final Context c;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<Listener> ls = new ArrayList<Listener>();
    private MediaSessionManager msm;
    private MediaController ctl;
    private boolean started;

    // kitaplik
    private MediaBrowser lib;
    private String libPkg;
    private MediaController libCtl;
    private final List<Section> sections = new ArrayList<Section>();
    private final List<Song> songs = new ArrayList<Song>();
    private String section = QUEUE;
    private boolean libFailed;

    private MusicHub(Context c) { this.c = c; }

    // ---------------- izin ----------------

    public static boolean hasAccess(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
            if (s == null) return false;
            String full = new ComponentName(c, MediaListener.class).flattenToString();
            for (String p : s.split(":")) if (p.equalsIgnoreCase(full)) return true;
        } catch (Exception e) { }
        return false;
    }

    public static void openAccess(Context c) {
        Intent i = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); } catch (Exception e) { PermissionHelper.openAppInfo(c); }
    }

    // ---------------- yasam dongusu ----------------

    public void add(Listener l) { if (!ls.contains(l)) ls.add(l); start(); }
    public void remove(Listener l) { ls.remove(l); }

    private void start() {
        if (started || !hasAccess(c)) { refresh(); return; }
        try {
            msm = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            msm.addOnActiveSessionsChangedListener(sessionsListener, new ComponentName(c, MediaListener.class), h);
            started = true;
        } catch (Exception e) { SplitLog.add("music: session listener failed: " + e); }
        refresh();
    }

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                public void onActiveSessionsChanged(List<MediaController> list) { refresh(); }
            };

    private List<MediaController> sessions() {
        try {
            if (msm == null) msm = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> l = msm.getActiveSessions(new ComponentName(c, MediaListener.class));
            List<MediaController> out = new ArrayList<MediaController>();
            if (l != null) for (MediaController m : l) if (!m.getPackageName().equals(c.getPackageName())) out.add(m);
            return out;
        } catch (Exception e) { return new ArrayList<MediaController>(); }
    }

    /** Secili uygulamanin oturumu; secim "otomatik" ise calan (yoksa ilk) oturum */
    public void refresh() {
        MediaController pick = null;
        if (hasAccess(c)) {
            List<MediaController> all = sessions();
            String pref = Prefs.musicPkg(c);
            for (MediaController m : all) if (pref != null && pref.equals(m.getPackageName())) { pick = m; break; }
            if (pick == null && pref == null) {
                for (MediaController m : all) if (playing(m)) { pick = m; break; }
                if (pick == null && !all.isEmpty()) pick = all.get(0);
            }
        }
        setController(pick);
        String p = pkg();
        if (p != null) {
            connectLibrary(p);
            // muzik uygulamasi yeniden baslatildiysa (or. kapaga basili tutup acinca) kitaplik baglantisi kopar: yeniden baglan
            if (lib != null && !lib.isConnected() && !libConnecting) reconnectLibrary("not connected");
        }
        fire(false);
    }

    private void setController(MediaController m) {
        if (ctl != null && m != null && ctl.getSessionToken().equals(m.getSessionToken())) return;
        if (ctl != null) try { ctl.unregisterCallback(cb); } catch (Exception e) { }
        ctl = m;
        if (ctl != null) {
            ctl.registerCallback(cb, h);
            Prefs.setMusicLast(c, ctl.getPackageName());
        }
        // yeni oturum (or. calma yeni basladi): siradakiler listesini tazele
        if (QUEUE.equals(section)) h.post(new Runnable() { public void run() { if (QUEUE.equals(section)) loadSection(); } });
    }

    private final MediaController.Callback cb = new MediaController.Callback() {
        public void onPlaybackStateChanged(PlaybackState s) { fire(false); }
        public void onMetadataChanged(MediaMetadata m) { fire(false); }
        public void onQueueChanged(List<MediaSession.QueueItem> q) { if (QUEUE.equals(section)) loadSection(); fire(false); }
        public void onSessionDestroyed() { h.post(new Runnable() { public void run() { refresh(); } }); }
    };

    private void fire(final boolean sectionsToo) {
        for (Listener l : new ArrayList<Listener>(ls)) {
            l.onMusicChanged();
            if (sectionsToo) l.onSectionsChanged();
        }
    }

    // ---------------- durum ----------------

    public MediaController controller() { return ctl; }

    /** Panelde gosterilen uygulama: oturum varsa onun paketi, yoksa secili / son kullanilan */
    public String pkg() {
        if (ctl != null) return ctl.getPackageName();
        String p = Prefs.musicPkg(c);
        if (p != null) return p;
        String last = Prefs.musicLast(c);
        return last != null && AppList.installed(c, last) ? last : null;
    }

    public MediaMetadata metadata() { try { return ctl == null ? null : ctl.getMetadata(); } catch (Exception e) { return null; } }
    public PlaybackState state() { try { return ctl == null ? null : ctl.getPlaybackState(); } catch (Exception e) { return null; } }

    public boolean isPlaying() { return playing(ctl); }

    private static boolean playing(MediaController m) {
        if (m == null) return false;
        PlaybackState s = m.getPlaybackState();
        if (s == null) return false;
        int st = s.getState();
        return st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING || st == PlaybackState.STATE_CONNECTING;
    }

    /** Anlik konum (ms), calarken gecen sure eklenir */
    public long position() {
        PlaybackState s = state();
        if (s == null) return 0;
        long p = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0) {
            p += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        long d = duration();
        if (d > 0 && p > d) p = d;
        return Math.max(0, p);
    }

    public long duration() {
        MediaMetadata m = metadata();
        return m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
    }

    public boolean canSeek() {
        PlaybackState s = state();
        return s != null && (s.getActions() & PlaybackState.ACTION_SEEK_TO) != 0 && duration() > 0;
    }

    // ---------------- kontroller ----------------

    public void playPause() {
        if (ctl != null) {
            if (isPlaying()) ctl.getTransportControls().pause();
            else ctl.getTransportControls().play();
            return;
        }
        startPlayback();
    }

    // ---------------- karistir / tekrarla ----------------
    // Uygulama kendi dugmesini sunuyorsa (or. SimpMusic "shuffle"/"repeat") o kullanilir; yoksa standart
    // MediaSessionCompat komutu gonderilir. Uygulamalar karistirma durumunu disariya bildirmedigi icin son durum burada tutulur.

    private static final String SET_SHUFFLE = "android.support.v4.media.session.action.SET_SHUFFLE_MODE";
    private static final String ARG_SHUFFLE = "android.support.v4.media.session.action.ARGUMENT_SHUFFLE_MODE";
    private static final String SET_REPEAT = "android.support.v4.media.session.action.SET_REPEAT_MODE";
    private static final String ARG_REPEAT = "android.support.v4.media.session.action.ARGUMENT_REPEAT_MODE";

    public PlaybackState.CustomAction customAction(String kind) {
        PlaybackState s = state();
        if (s == null || s.getCustomActions() == null) return null;
        String rx = "shuffle".equals(kind) ? "(shuffle|kar[ıi][sş]t[ıi]r|aleatori|zuf[aä]ll|al[eé]atoire|перемеш)" : "(repeat|tekrar|wiederhol|r[eé]p[eé]t|repetir|ripet|повтор)";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(rx, java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
        for (PlaybackState.CustomAction a : s.getCustomActions()) {
            String act = a.getAction() == null ? "" : a.getAction();
            CharSequence nm = a.getName();
            if (p.matcher(act).find() || (nm != null && p.matcher(nm).find())) return a;
        }
        return null;
    }

    /** Uygulamanin kendi dugme simgesi (durumu gosterir, or. tekrar: kapali / tumu / tek) */
    public android.graphics.drawable.Drawable customIcon(PlaybackState.CustomAction a) {
        if (a == null || ctl == null || a.getIcon() == 0) return null;
        try {
            return c.getPackageManager().getResourcesForApplication(ctl.getPackageName()).getDrawable(a.getIcon(), null);
        } catch (Throwable e) { return null; }
    }

    public boolean shuffleOn() { return ctl != null && Prefs.mget(c, "musicShuffle_" + ctl.getPackageName(), false); }
    /** 0 kapali, 1 tumu, 2 tek sarki */
    private String lastRepeatName;

    /**
     * 0 kapali, 1 tumu, 2 tek sarki. Panelde dokunulan son durum esas alinir; uygulamanin dugme adi
     * (or. "Repeat One") degistiginde ona gore guncellenir (uygulamanin icinden degistirilirse de dogru gorunur).
     */
    public int repeatMode() {
        if (ctl == null) return 0;
        String key = "musicRepeat_" + ctl.getPackageName();
        PlaybackState.CustomAction a = customAction("repeat");
        String name = a == null || a.getName() == null ? null : a.getName().toString();
        if (name != null && repeatUnsupported()) { int p = parseRepeat(name); return p < 0 ? 0 : p; }
        if (name != null && !name.equals(lastRepeatName)) {
            lastRepeatName = name;
            int p = parseRepeat(name);
            if (p >= 0) Prefs.msetLong(c, key, p);
        }
        return (int) Prefs.mgetLong(c, key);
    }

    private static int parseRepeat(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.matches(".*(\\boff\\b|kapal|\\baus\\b|d[eé]sactiv|выкл).*")) return 0;
        if (n.matches(".*(\\bone\\b|tek [sş]ark|bir [sş]ark|\\b1\\b|einzel|один).*")) return 2;
        if (n.matches(".*(\\ball\\b|t[uü]m[uü]|hepsi|\\balle\\b|tous|todo|все).*")) return 1;
        return -1;
    }

    public boolean repeatJustDisabled;

    /** Bu uygulama (bu surumuyle) disaridan tekrar ayarini kabul etmiyor */
    public boolean repeatUnsupported() {
        if (ctl == null) return false;
        String p = ctl.getPackageName();
        long v = Prefs.msp(c).getLong("musicRepeatNo_" + p, -1);
        return v >= 0 && v == versionOf(p);
    }

    private long versionOf(String p) {
        try { return c.getPackageManager().getPackageInfo(p, 0).versionCode; } catch (Exception e) { return 0; }
    }

    public int queueSize() {
        try { List<MediaSession.QueueItem> q = ctl == null ? null : ctl.getQueue(); return q == null ? 0 : q.size(); }
        catch (Exception e) { return 0; }
    }

    public void toggleShuffle() {
        if (ctl == null) return;
        boolean on = !shuffleOn();
        PlaybackState.CustomAction a = customAction("shuffle");
        try {
            if (a != null) ctl.getTransportControls().sendCustomAction(a, null);
            else {
                Bundle b = new Bundle();
                b.putInt(ARG_SHUFFLE, on ? 1 : 0);
                ctl.getTransportControls().sendCustomAction(SET_SHUFFLE, b);
            }
        } catch (Exception e) { SplitLog.add("music: shuffle failed: " + e); }
        Prefs.mset(c, "musicShuffle_" + ctl.getPackageName(), on);
        SplitLog.add("music: shuffle " + (on ? "on" : "off") + (a != null ? " (app button)" : ""));
        fire(false);
    }

    /**
     * Tekrar modu: kapali -> tek -> tumu -> kapali. Iki yol birlikte denenir: once uygulamanin kendi tekrar
     * dugmesi (varsa, ayni sirada ilerler), ardindan standart SET_REPEAT_MODE ile hedef mod acikca ayarlanir.
     * Hangisi islerse islesin sonuc hedef moddur.
     */
    public void cycleRepeat() {
        if (ctl == null) return;
        int cur = repeatMode();
        final int m = cur == 0 ? 2 : cur == 2 ? 1 : 0;
        final PlaybackState.CustomAction a = customAction("repeat");
        final MediaController mc = ctl;
        final String before = a == null || a.getName() == null ? null : a.getName().toString();
        try { if (a != null) mc.getTransportControls().sendCustomAction(a, null); }
        catch (Exception e) { SplitLog.add("music: repeat app button failed: " + e); }
        h.postDelayed(new Runnable() {
            public void run() {
                try {
                    Bundle b = new Bundle();
                    b.putInt(ARG_REPEAT, m == 1 ? 2 : m == 2 ? 1 : 0);   // compat: 0 yok, 1 tek, 2 tumu
                    mc.getTransportControls().sendCustomAction(SET_REPEAT, b);
                } catch (Exception e) { SplitLog.add("music: repeat standard failed: " + e); }
            }
        }, a != null ? 400 : 0);
        Prefs.msetLong(c, "musicRepeat_" + ctl.getPackageName(), m);
        SplitLog.add("music: repeat " + (m == 0 ? "off" : m == 1 ? "all" : "one") + (a != null ? " (app button + standard)" : " (standard)"));
        fire(false);
        // uygulamanin tekrar dugmesi bizim komutumuzdan sonra hic degismiyorsa uygulama disaridan tekrar ayarini
        // kabul etmiyor demektir (or. SimpMusic): bu uygulama surumu icin dugme gizlenir
        if (before != null) h.postDelayed(new Runnable() {
            public void run() {
                PlaybackState.CustomAction b = customAction("repeat");
                String after = b == null || b.getName() == null ? null : b.getName().toString();
                if (mc == ctl && before.equals(after)) {
                    Prefs.msp(c).edit().putLong("musicRepeatNo_" + mc.getPackageName(), versionOf(mc.getPackageName())).apply();
                    repeatJustDisabled = true;
                    SplitLog.add("music: " + mc.getPackageName() + " ignores repeat from outside, button hidden");
                    fire(false);
                }
            }
        }, 2500);
    }

    public void next() { if (ctl != null) ctl.getTransportControls().skipToNext(); }
    public void prev() { if (ctl != null) ctl.getTransportControls().skipToPrevious(); }
    public void seek(long ms) { if (ctl != null) ctl.getTransportControls().seekTo(ms); }

    /**
     * Oturum yokken cal: 1) uygulamanin medya hizmetine arka planda baglanip "cal" (son calinan yerden),
     * 2) olmazsa medya tusu, 3) o da olmazsa uygulamayi kisa sure acip calistir, ikiliyi geri getir.
     */
    public void startPlayback() {
        final String p = pkg();
        if (p == null) { SplitLog.add("music: no music app selected"); return; }
        SplitLog.add("music: starting playback in " + p);
        ComponentName svc = browseService(p);
        if (svc == null) { mediaKey(p); return; }
        Bundle hints = new Bundle();
        hints.putBoolean(EXTRA_RECENT, true);
        final MediaBrowser[] holder = new MediaBrowser[1];
        final boolean[] done = new boolean[1];
        holder[0] = new MediaBrowser(c, svc, new MediaBrowser.ConnectionCallback() {
            public void onConnected() {
                if (done[0]) return;
                done[0] = true;
                try {
                    MediaController m = new MediaController(c, holder[0].getSessionToken());
                    m.getTransportControls().play();
                    SplitLog.add("music: play sent via media service");
                    h.postDelayed(new Runnable() { public void run() { refresh(); } }, 1200);
                    h.postDelayed(new Runnable() { public void run() { checkStarted(p); } }, 3500);
                } catch (Exception e) { mediaKey(p); }
                final MediaBrowser b = holder[0];
                h.postDelayed(new Runnable() { public void run() { try { b.disconnect(); } catch (Exception e) { } } }, 8000);
            }
            public void onConnectionFailed() { if (!done[0]) { done[0] = true; SplitLog.add("music: media service refused"); mediaKey(p); } }
            public void onConnectionSuspended() { }
        }, hints);
        try { holder[0].connect(); } catch (Exception e) { mediaKey(p); }
        h.postDelayed(new Runnable() {
            public void run() { if (!done[0]) { done[0] = true; try { holder[0].disconnect(); } catch (Exception e) { } mediaKey(p); } }
        }, 4000);
    }

    private void mediaKey(final String p) {
        try {
            if (p.equals(Prefs.musicLast(c))) {
                // son calan uygulama: sistem medya tusunu ona iletir (Bluetooth play tusu gibi); kapaliysa uygulamayi arka planda baslatir
                AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));
                SplitLog.add("music: media key sent");
            }
            // ayrica dogrudan uygulamanin medya dugmesi alicisina gonder
            for (int a : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(p)
                        .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(a, KeyEvent.KEYCODE_MEDIA_PLAY));
                c.sendBroadcast(i);
            }
            SplitLog.add("music: media button sent to " + p);
        } catch (Exception e) { SplitLog.add("music: media key failed: " + e); }
        // kapali uygulamanin arka planda acilip calmaya baslamasi Tbox'ta birkac saniye surebilir:
        // uygulamayi on plana (tam ekran) acmadan once yeterince bekle
        h.postDelayed(new Runnable() { public void run() { refresh(); } }, 2500);
        h.postDelayed(new Runnable() { public void run() { refresh(); checkStarted(p); } }, 6000);
    }

    private boolean openedOnce;

    /** Hala calmiyorsa uygulamayi ac, oturum gelince cal ve ikiliyi geri getir */
    private void checkStarted(final String p) {
        refresh();
        if (isPlaying() && p.equals(pkg())) return;
        if (openedOnce) return;
        openedOnce = true;
        h.postDelayed(new Runnable() { public void run() { openedOnce = false; } }, 20000);
        Intent i = c.getPackageManager().getLaunchIntentForPackage(p);
        if (i == null) return;
        SplitLog.add("music: opening " + p + " to start playback");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); } catch (Exception e) { return; }
        // uygulama acilip oturumunu kurana kadar bekle (en fazla ~10 sn), cal, sonra ikiliyi geri getir
        final long t0 = SystemClock.uptimeMillis();
        h.postDelayed(new Runnable() {
            int tries;
            public void run() {
                refresh();
                boolean got = ctl != null && p.equals(ctl.getPackageName());
                if (got && !isPlaying() && tries++ % 2 == 0) ctl.getTransportControls().play();
                boolean ok = got && isPlaying();
                if (ok || SystemClock.uptimeMillis() - t0 > 10000) {
                    SplitLog.add(ok ? "music: playing, back to pair" : "music: " + p + " did not start (" + (got ? "session, not playing" : "no session") + "), back to pair");
                    OverlayService.requestResumePair(c);
                    return;
                }
                h.postDelayed(this, 800);
            }
        }, 1500);
    }

    // ---------------- kitaplik ve calma sirasi ----------------

    public List<Section> sections() { return sections; }
    public List<Song> songs() { return songs; }
    public String section() { return section; }
    public boolean libraryFailed() { return libFailed; }

    private static ComponentName browseServiceOf(Context c, String p) {
        try {
            List<ResolveInfo> l = c.getPackageManager().queryIntentServices(new Intent(BROWSE_ACTION).setPackage(p), 0);
            if (l == null || l.isEmpty()) return null;
            return new ComponentName(p, l.get(0).serviceInfo.name);
        } catch (Exception e) { return null; }
    }
    private ComponentName browseService(String p) { return browseServiceOf(c, p); }

    private void connectLibrary(final String p) {
        if (p.equals(libPkg)) return;
        disconnectLibrary();
        libPkg = p;
        libFailed = false;
        sections.clear();
        wanted = Prefs.musicSection(c, p);
        section = QUEUE;
        rebuildSections(null);
        loadSection();
        ComponentName svc = browseService(p);
        if (svc == null) { libFailed = true; fire(true); return; }
        final MediaBrowser b = new MediaBrowser(c, svc, new MediaBrowser.ConnectionCallback() {
            public void onConnected() {
                libConnecting = false;
                if (lib == null) return;
                try { libCtl = new MediaController(c, lib.getSessionToken()); } catch (Exception e) { libCtl = null; }
                final String root = lib.getRoot();
                SplitLog.add("music: library connected (" + p + ")");
                lib.subscribe(root, new MediaBrowser.SubscriptionCallback() {
                    public void onChildrenLoaded(String id, List<MediaBrowser.MediaItem> items) {
                        rootItems = items;
                        rebuildSections(items);
                        // son secilen bolum (kitaplik yuklenince geri gelir)
                        if (wanted != null && find(wanted) != null) section = wanted;
                        wanted = null;
                        loadSection();
                    }
                    public void onError(String id) { libFailed = true; fire(true); }
                });
            }
            public void onConnectionFailed() {
                libConnecting = false;
                SplitLog.add("music: library not shared by " + p);
                libFailed = true;
                if (!QUEUE.equals(section)) { section = QUEUE; loadSection(); }
                fire(true);
            }
            public void onConnectionSuspended() {
                h.postDelayed(new Runnable() { public void run() { reconnectLibrary("connection lost"); } }, 1000);
            }
        }, null);
        lib = b;
        libConnecting = true;
        try { b.connect(); } catch (Exception e) { libFailed = true; libConnecting = false; }
    }

    private boolean libConnecting;
    private long lastReconnect;

    /** Kitaplik baglantisini ayni uygulamaya yeniden kurar; acik sekme (sabitlenmis olanlar dahil) korunur */
    private void reconnectLibrary(String why) {
        if (libPkg == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastReconnect < 3000) return;
        lastReconnect = now;
        String p = libPkg, keep = section;
        SplitLog.add("music: library " + why + ", reconnecting");
        disconnectLibrary();
        connectLibrary(p);
        if (!QUEUE.equals(keep)) wanted = keep;
    }

    private List<MediaBrowser.MediaItem> rootItems;
    private String wanted;

    private void disconnectLibrary() {
        if (lib != null) try { lib.disconnect(); } catch (Exception e) { }
        lib = null; libCtl = null; libPkg = null; rootItems = null;
        warmed.clear();
        path.clear();
    }

    /** Sekmeler: Siradakiler + kitapligin ust bolumleri + acilan alt bolumler (duz) */
    private void rebuildSections(List<MediaBrowser.MediaItem> items) {
        sections.clear();
        sections.add(new Section(QUEUE, c.getString(R.string.music_queue)));
        // kullanicinin sabitledigi listeler en ustte (Siradakiler'den hemen sonra)
        for (Section pin : pins()) if (find(pin.id) == null) sections.add(pin);
        boolean rootSongs = false;
        if (items != null) for (MediaBrowser.MediaItem it : items) {
            if (it.isBrowsable()) sections.add(new Section(it.getMediaId(), title(it)));
            else if (it.isPlayable()) rootSongs = true;
        }
        if (rootSongs && lib != null) sections.add(1, new Section(lib.getRoot(), c.getString(R.string.music_library)));
        if (find(section) == null) section = QUEUE;
    }

    private Section find(String id) {
        for (Section s : sections) if (s.id.equals(id)) return s;
        return null;
    }

    private static String title(MediaBrowser.MediaItem it) {
        CharSequence t = it.getDescription().getTitle();
        return t == null ? "" : t.toString();
    }

    // ---------------- sabitlenen listeler ----------------

    private List<Section> pins() {
        List<Section> out = new ArrayList<Section>();
        if (libPkg == null) return out;
        String raw = Prefs.msp(c).getString("musicPins_" + libPkg, "");
        for (String line : raw.split("\n")) {
            String[] f = line.split("\t");
            if (f.length < 2) continue;
            Section sec = new Section(f[1], f[0]);
            sec.pinned = true;
            for (int i = 2; i < f.length; i++) sec.chain.add(f[i]);
            out.add(sec);
        }
        return out;
    }

    private void savePins(List<Section> l) {
        StringBuilder sb = new StringBuilder();
        for (Section p : l) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(p.title.replace('\t', ' ').replace('\n', ' ')).append('\t').append(p.id);
            for (String a : p.chain) sb.append('\t').append(a);
        }
        Prefs.msp(c).edit().putString("musicPins_" + libPkg, sb.toString()).apply();
    }

    /** Acik listedeki bir klasoru (or. "Listem") sekme olarak sabitler */
    public boolean pin(Song folder) {
        if (libPkg == null || folder == null || !folder.folder || folder.id == null) return false;
        List<Section> l = pins();
        for (Section p : l) if (p.id.equals(folder.id)) return false;
        Section sec = new Section(folder.id, folder.title);
        Section cur = find(section);
        if (cur != null && cur.pinned) sec.chain.addAll(cur.chain);
        sec.chain.add(section);
        for (Song f : path) sec.chain.add(f.id);
        l.add(sec);
        savePins(l);
        SplitLog.add("music: pinned " + folder.title);
        rebuildSections(rootItems);
        fire(true);
        return true;
    }

    public void unpin(String id) {
        SplitLog.add("music: unpinned " + id);
        List<Section> l = pins();
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).id.equals(id)) l.remove(i);
        savePins(l);
        if (id.equals(section)) { section = QUEUE; path.clear(); }
        rebuildSections(rootItems);
        loadSection();
    }

    public boolean isPinned(String id) {
        Section sec = find(id);
        return sec != null && sec.pinned;
    }

    private final java.util.Set<String> warmed = new java.util.HashSet<String>();

    /** Ust klasorleri sirayla bir kez yukler (uygulamanin alt listeyi hazirlamasi icin), sonra devam eder */
    private void warm(final List<String> ids, final int i, final Runnable then) {
        if (i >= ids.size() || lib == null || !lib.isConnected()) { then.run(); return; }
        final String id = ids.get(i);
        if (warmed.contains(id)) { warm(ids, i + 1, then); return; }
        final boolean[] done = new boolean[1];
        final MediaBrowser b = lib;
        final Runnable next = new Runnable() {
            public void run() {
                if (done[0]) return;
                done[0] = true;
                warmed.add(id);
                try { b.unsubscribe(id); } catch (Exception e) { }
                warm(ids, i + 1, then);
            }
        };
        b.subscribe(id, new MediaBrowser.SubscriptionCallback() {
            public void onChildrenLoaded(String pid, List<MediaBrowser.MediaItem> items) { next.run(); }
            public void onError(String pid) { next.run(); }
        });
        h.postDelayed(next, 15000);
    }

    public void selectSection(String id) {
        section = id;
        path.clear();
        if (libPkg != null) Prefs.setMusicSection(c, libPkg, id);
        loadSection();
    }

    // bolum icindeki klasorler (or. Playlists > bir calma listesi): tek seviyelik gezinti
    private final List<Song> path = new ArrayList<Song>();

    /** Acik klasorun adi (geri satiri icin), bolumun kendisindeyse null */
    public String folderTitle() { return path.isEmpty() ? null : path.get(path.size() - 1).title; }

    public void openFolder(Song f) {
        path.add(f);
        loadSection();
    }

    public void back() {
        if (!path.isEmpty()) path.remove(path.size() - 1);
        loadSection();
    }

    private String subscribed;

    private void loadSection() {
        songs.clear();
        fire(true);   // liste hemen bosalsin (ListView tutarliligi)
        if (QUEUE.equals(section)) {
            List<MediaSession.QueueItem> q = null;
            try { q = ctl == null ? null : ctl.getQueue(); } catch (Exception e) { }
            if (q != null) for (MediaSession.QueueItem it : q) {
                Song s = song(it.getDescription());
                s.queueId = it.getQueueId();
                songs.add(s);
            }
            fire(true);
            return;
        }
        if (lib == null || !lib.isConnected()) {
            if (lib != null && !libConnecting) reconnectLibrary("not connected");
            fire(true);
            return;
        }
        final String id = path.isEmpty() ? section : path.get(path.size() - 1).id;
        final String name = path.isEmpty() ? sectionTitle(section) : folderTitle();
        if (subscribed != null) try { lib.unsubscribe(subscribed); } catch (Exception e) { }
        subscribed = id;
        Section cur = find(section);
        List<String> pre = new ArrayList<String>();
        if (cur != null && cur.pinned) {
            pre.addAll(cur.chain);
            if (!path.isEmpty()) { pre.add(section); for (int i = 0; i < path.size() - 1; i++) pre.add(path.get(i).id); }
        }
        warm(pre, 0, new Runnable() {
            public void run() { if (id.equals(subscribed) && lib != null && lib.isConnected()) subscribeList(id, name); }
        });
    }

    private void subscribeList(final String id, final String name) {
        lib.subscribe(id, new MediaBrowser.SubscriptionCallback() {
            public void onChildrenLoaded(String pid, List<MediaBrowser.MediaItem> items) {
                if (!pid.equals(subscribed)) return;
                songs.clear();
                int folders = 0, tracks = 0;
                // once klasorler (calma listeleri, albumler), sonra sarkilar
                for (MediaBrowser.MediaItem it : items) {
                    if (!it.isBrowsable()) continue;
                    Song f = song(it.getDescription());
                    f.id = it.getMediaId();
                    f.folder = true;
                    songs.add(f);
                    folders++;
                }
                for (MediaBrowser.MediaItem it : items) {
                    if (it.isBrowsable() || !it.isPlayable()) continue;
                    Song s = song(it.getDescription());
                    s.id = it.getMediaId();
                    songs.add(s);
                    tracks++;
                }
                warmed.add(pid);
                SplitLog.add("music: " + name + " -> " + tracks + " songs, " + folders + " folders");
                fire(true);
            }
            public void onError(String pid) {
                SplitLog.add("music: " + name + " -> error");
                songs.clear();
                fire(true);
            }
        });
    }

    private String sectionTitle(String id) {
        Section s = find(id);
        return s == null ? id : s.title;
    }

    private static Song song(android.media.MediaDescription d) {
        Song s = new Song();
        s.id = d.getMediaId();
        s.title = d.getTitle() == null ? "" : d.getTitle().toString();
        CharSequence sub = d.getSubtitle();
        s.sub = sub == null ? "" : sub.toString();
        s.icon = d.getIconBitmap();
        s.iconUri = d.getIconUri();
        return s;
    }

    public void playSong(Song s) {
        if (s.folder) { openFolder(s); return; }
        try {
            if (s.queueId >= 0 && ctl != null) { ctl.getTransportControls().skipToQueueItem(s.queueId); return; }
            if (s.id == null) return;
            MediaController m = libCtl != null ? libCtl : ctl;
            if (m != null) m.getTransportControls().playFromMediaId(s.id, null);
            h.postDelayed(new Runnable() { public void run() { refresh(); } }, 1200);
        } catch (Exception e) { SplitLog.add("music: play item failed: " + e); }
    }

    /** Ad (ve varsa sanatci) ile listelerde sarki arar: once acik liste, sonra calma sirasi */
    public Song findSong(String title, String artist) {
        if (title == null) return null;
        for (Song s : songs) if (!s.folder && title.equals(s.title) && (s.icon != null || s.iconUri != null)) return s;
        try {
            List<MediaSession.QueueItem> q = ctl == null ? null : ctl.getQueue();
            if (q != null) for (MediaSession.QueueItem it : q) {
                CharSequence t = it.getDescription().getTitle();
                if (t != null && title.equals(t.toString())) {
                    Song s = song(it.getDescription());
                    if (s.icon != null || s.iconUri != null) return s;
                }
            }
        } catch (Exception e) { }
        return null;
    }

    /** Su an calan sira ogesi */
    public long activeQueueId() {
        PlaybackState s = state();
        return s == null ? -1 : s.getActiveQueueItemId();
    }

    // ---------------- kaynak secimi ----------------

    /** Muzik calabilen uygulamalar (medya hizmeti ya da medya tusu alici) + aktif oturumlar */
    public static List<String> musicApps(Context c) {
        PackageManager pm = c.getPackageManager();
        LinkedHashSet<String> set = new LinkedHashSet<String>();
        try {
            for (ResolveInfo r : pm.queryIntentServices(new Intent(BROWSE_ACTION), 0)) set.add(r.serviceInfo.packageName);
        } catch (Exception e) { }
        try {
            for (ResolveInfo r : pm.queryBroadcastReceivers(new Intent(Intent.ACTION_MEDIA_BUTTON), 0)) set.add(r.activityInfo.packageName);
        } catch (Exception e) { }
        set.remove(c.getPackageName());
        List<String> out = new ArrayList<String>();
        for (String p : set) if (pm.getLaunchIntentForPackage(p) != null) out.add(p);
        final Collator col = Collator.getInstance();
        final Context cc = c;
        Collections.sort(out, new Comparator<String>() {
            public int compare(String a, String b) { return col.compare(AppList.label(cc, a), AppList.label(cc, b)); }
        });
        return out;
    }

    public void choose(String p) {
        Prefs.setMusicPkg(c, p);
        if (p != null) Prefs.setMusicLast(c, p);
        SplitLog.add("music: source " + (p == null ? "auto" : p));
        disconnectLibrary();
        if (ctl != null) try { ctl.unregisterCallback(cb); } catch (Exception e) { }
        ctl = null;
        refresh();
        fire(true);
    }
}
