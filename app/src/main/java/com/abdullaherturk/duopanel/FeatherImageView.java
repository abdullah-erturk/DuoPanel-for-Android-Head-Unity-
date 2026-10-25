package com.abdullaherturk.duopanel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.widget.ImageView;

/**
 * Kenarlari yumusakca arka plana karisan kapak (AIMP benzeri): resim cizildikten sonra dort kenara
 * seffaflasan maske uygulanir; koseler daha da yumusar.
 */
public class FeatherImageView extends ImageView {
    private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient hg, vg;
    private int gw, gh;
    private boolean feather;
    private float amount = 0.16f;

    public FeatherImageView(Context c) {
        super(c);
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    public void setFeather(boolean on) {
        if (feather == on) return;
        feather = on;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (!feather || w == 0 || h == 0) { super.onDraw(c); return; }
        if (w != gw || h != gh || hg == null) {
            gw = w; gh = h;
            float fx = Math.min(0.45f, Math.min(w, h) * amount / w), fy = Math.min(0.45f, Math.min(w, h) * amount / h);
            int[] cols = {0x00000000, 0xFF000000, 0xFF000000, 0x00000000};
            hg = new LinearGradient(0, 0, w, 0, cols, new float[]{0f, fx, 1f - fx, 1f}, Shader.TileMode.CLAMP);
            vg = new LinearGradient(0, 0, 0, h, cols, new float[]{0f, fy, 1f - fy, 1f}, Shader.TileMode.CLAMP);
        }
        int sc = c.saveLayer(0, 0, w, h, null);
        super.onDraw(c);
        mask.setShader(hg);
        c.drawRect(0, 0, w, h, mask);
        mask.setShader(vg);
        c.drawRect(0, 0, w, h, mask);
        c.restoreToCount(sc);
    }
}
