package com.alexspec.specialist;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.SparseArray;
import android.view.MotionEvent;

import com.alexspec.specialist.core.MemTrace;
import com.alexspec.specialist.core.Specialist;

import java.util.ArrayList;
import java.util.List;

/**
 * The live memory map: two layouts, pinch zoom / two-finger pan, a tap inspector and the
 * guided tutorial overlay. Owned and laid out by {@link MainView}.
 */
final class MapPanel {
    interface Host {
        void invalidate();

        void postInvalidateDelayed(long ms);

        void onTutorialClosed();
    }

    private final EmuThread emu;
    private final Specialist machine;
    private final MemTrace trace;
    private final Host host;
    private final float dp;

    final RectF rect = new RectF();
    /** area for the tutorial panel (set by the owner) */
    final RectF panelRect = new RectF();
    String programTitle = "";

    // pictures from the emulation thread
    private final Bitmap[] bmp = {
        Bitmap.createBitmap(MemTrace.width(MemTrace.LAYOUT_BITS), MemTrace.height(MemTrace.LAYOUT_BITS), Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(MemTrace.width(MemTrace.LAYOUT_BYTES), MemTrace.height(MemTrace.LAYOUT_BYTES), Bitmap.Config.ARGB_8888),
    };
    private final int[] pix = new int[MemTrace.MAP_W * MemTrace.MAP_H];
    private int shownVersion = -1;
    private int shownLayout = -1;

    // view transform: zoom 1 = whole map fits; (cx, cy) = map point at the rect centre
    private float zoom = 1f, cx = -1, cy = -1;
    private int viewLayout = -1;

    // touches
    private final SparseArray<float[]> ptrs = new SparseArray<>();
    private boolean gesture;
    private float g0Dist, g0Zoom, g0Mx, g0My;
    private long lastTapTime;
    private float lastTapX, lastTapY, downX, downY;
    private boolean moved;
    private float dragMx, dragMy;

    // inspector
    private int inspectAddr = -1;
    private long inspectUntil;
    private boolean inspectTouching;

    // tutorial
    private int step = -1;
    private float textScroll;
    private StaticLayout textLayout;
    private int layoutStep = -2;
    private float layoutWidth;
    private final RectF btnPrev = new RectF(), btnNext = new RectF(), btnClose = new RectF();
    private int panelPtr = -1;
    private float panelLastY;
    private boolean panelDragged;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint();
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    /** own paint for the tutorial text: StaticLayout keeps using the paint it was built with */
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();

    MapPanel(EmuThread emu, Host host, float dp) {
        this.emu = emu;
        this.machine = emu.machine;
        this.trace = emu.machine.trace;
        this.host = host;
        this.dp = dp;
        bmpPaint.setFilterBitmap(false);
    }

    // ------------------------------------------------------------------ geometry

    private int layout() {
        return trace.layout;
    }

    private float cw() {
        return MemTrace.width(layout());
    }

    private float ch() {
        return MemTrace.height(layout());
    }

    /** screen pixels per map pixel */
    private float scale() {
        return Math.min(rect.width() / cw(), rect.height() / ch()) * zoom;
    }

    private float maxZoom() {
        // bits: up to ~10 screen px per bit; bytes: up to ~80 px per byte (bits ~10 px)
        float base = Math.min(rect.width() / cw(), rect.height() / ch());
        return Math.max(1f, (layout() == MemTrace.LAYOUT_BITS ? 10f : 80f) * dp / 2.75f / base);
    }

    private void clampView() {
        if (viewLayout != layout() || cx < 0) {
            viewLayout = layout();
            zoom = 1f;
            cx = cw() / 2f;
            cy = ch() / 2f;
        }
        zoom = Math.max(1f, Math.min(maxZoom(), zoom));
        float s = scale();
        float hw = rect.width() / 2f / s, hh = rect.height() / 2f / s;
        cx = hw * 2 >= cw() ? cw() / 2f : Math.max(hw, Math.min(cw() - hw, cx));
        cy = hh * 2 >= ch() ? ch() / 2f : Math.max(hh, Math.min(ch() - hh, cy));
    }

    private float toScreenX(float mx) {
        return rect.centerX() + (mx - cx) * scale();
    }

    private float toScreenY(float my) {
        return rect.centerY() + (my - cy) * scale();
    }

    private float toMapX(float sx) {
        return cx + (sx - rect.centerX()) / scale();
    }

    private float toMapY(float sy) {
        return cy + (sy - rect.centerY()) / scale();
    }

    void resetView() {
        viewLayout = -1;
        clampView();
    }

    // ------------------------------------------------------------------ drawing

    void draw(Canvas c) {
        clampView();
        int layout = layout();
        int ver = emu.mapVersion;
        if (ver != shownVersion) {
            int got;
            synchronized (emu.mapFrame) {
                got = emu.mapLayout;
                int n = MemTrace.width(got) * MemTrace.height(got);
                System.arraycopy(emu.mapFrame, 0, pix, 0, n);
            }
            bmp[got].setPixels(pix, 0, MemTrace.width(got), 0, 0, MemTrace.width(got), MemTrace.height(got));
            shownVersion = ver;
            shownLayout = got;
        }

        c.save();
        c.clipRect(rect);
        c.drawColor(0xFF000000);
        float s = scale();
        if (shownLayout == layout) {
            matrix.reset();
            matrix.postScale(s, s);
            matrix.postTranslate(toScreenX(0), toScreenY(0));
            c.drawBitmap(bmp[layout], matrix, bmpPaint);
            if (layout == MemTrace.LAYOUT_BYTES && s >= 16 * dp / 2.75f) drawByteDetail(c, s);
        }

        // tutorial highlight: dim everything except the step's region
        Tutorial.Step st = step >= 0 ? Tutorial.STEPS[step] : null;
        if (st != null && (st.ranges != null || st.stack)) {
            Path region = regionPath(stepRanges(st));
            c.save();
            if (android.os.Build.VERSION.SDK_INT >= 26) c.clipOutPath(region);
            else c.clipPath(region, android.graphics.Region.Op.DIFFERENCE);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xB0000000);
            c.drawRect(rect, paint);
            c.restore();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * dp);
            paint.setColor(0xFFFFD040);
            c.drawPath(region, paint);
        }

        // inspector marker
        boolean showInfo = inspectAddr >= 0 && (inspectTouching || System.currentTimeMillis() < inspectUntil);
        if (showInfo) {
            float bx = toScreenX(MemTrace.posX(layout, inspectAddr));
            float by = toScreenY(MemTrace.posY(layout, inspectAddr));
            float bw = MemTrace.cellW(layout) * s, bh = s;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * dp);
            paint.setColor(0xFFFFFFFF);
            float m = 2 * dp;
            c.drawRect(bx - m, by - m, bx + bw + m, by + bh + m, paint);
        }
        c.restore();

        // zoom badge
        if (zoom > 1.01f) {
            String z = String.format(java.util.Locale.US, "×%.1f", zoom);
            tp.setTextSize(11 * dp);
            tp.setColor(0xFFFFFFFF);
            float w = tp.measureText(z) + 10 * dp;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x99000000);
            c.drawRoundRect(rect.right - w - 4 * dp, rect.top + 4 * dp, rect.right - 4 * dp, rect.top + 20 * dp, 6 * dp, 6 * dp, paint);
            c.drawText(z, rect.right - w - 4 * dp + 5 * dp, rect.top + 16 * dp, tp);
        }

        if (showInfo) drawInfo(c, layout);
        if (step >= 0) drawPanel(c);
    }

    /** byte layout zoomed in: draw the bits of the visible bytes as 8 thin bars per cell */
    private void drawByteDetail(Canvas c, float s) {
        int c0 = Math.max(0, (int) Math.floor(toMapX(rect.left)));
        int c1 = Math.min(255, (int) Math.floor(toMapX(rect.right)));
        int r0 = Math.max(0, (int) Math.floor(toMapY(rect.top)));
        int r1 = Math.min(255, (int) Math.floor(toMapY(rect.bottom)));
        int nc = c1 - c0 + 1, nr = r1 - r0 + 1;
        if (nc <= 0 || nr <= 0) return;
        int w = nc * 8;
        int[] px = new int[w * nr];
        int[] col = new int[2];
        for (int r = 0; r < nr; r++) {
            for (int k = 0; k < nc; k++) {
                int addr = ((r0 + r) << 8) | (c0 + k);
                int v = machine.rawRead(addr);
                trace.colors(addr, col);
                int p = r * w + k * 8;
                for (int b = 0; b < 8; b++, v <<= 1) px[p + b] = (v & 0x80) != 0 ? col[0] : col[1];
            }
        }
        Bitmap detail = Bitmap.createBitmap(px, w, nr, Bitmap.Config.ARGB_8888);
        RectF dst = new RectF(toScreenX(c0), toScreenY(r0), toScreenX(c1 + 1), toScreenY(r1 + 1));
        c.drawBitmap(detail, null, dst, bmpPaint);
        // thin separators between bytes
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x66000000);
        for (int k = c0; k <= c1 + 1; k++) {
            float x = toScreenX(k);
            c.drawRect(x - 0.5f, dst.top, x + 0.5f, dst.bottom, paint);
        }
        for (int r = r0; r <= r1 + 1; r++) {
            float y = toScreenY(r);
            c.drawRect(dst.left, y - 0.5f, dst.right, y + 0.5f, paint);
        }
        detail.recycle();
    }

    private int[][] stepRanges(Tutorial.Step st) {
        if (st.stack) {
            int sp = machine.cpu.sp & 0xFFFF;
            return new int[][]{{Math.max(0, sp - 32), Math.min(0xFFFF, sp + 32)}};
        }
        return st.ranges;
    }

    /** outline of an address range set, in screen coordinates */
    private Path regionPath(int[][] ranges) {
        Path p = new Path();
        int layout = layout();
        for (int[] r : ranges) {
            for (RectF m : rangeRects(layout, r[0], r[1])) {
                p.addRect(toScreenX(m.left), toScreenY(m.top), toScreenX(m.right), toScreenY(m.bottom), Path.Direction.CW);
            }
        }
        return p;
    }

    /** map-space rectangles covering addresses a..b */
    static List<RectF> rangeRects(int layout, int a, int b) {
        List<RectF> out = new ArrayList<>();
        if (layout == MemTrace.LAYOUT_BITS) {
            int c0 = a >> 8, c1 = b >> 8;
            for (int col = c0; col <= c1; col++) {
                int y0 = col == c0 ? a & 255 : 0, y1 = col == c1 ? b & 255 : 255;
                RectF last = out.isEmpty() ? null : out.get(out.size() - 1);
                if (last != null && y0 == 0 && y1 == 255 && last.top == 0 && last.bottom == 256 && last.right == col * 8) {
                    last.right += 8;
                } else {
                    out.add(new RectF(col * 8, y0, col * 8 + 8, y1 + 1));
                }
            }
        } else {
            int r0 = a >> 8, r1 = b >> 8;
            for (int row = r0; row <= r1; row++) {
                int x0 = row == r0 ? a & 255 : 0, x1 = row == r1 ? b & 255 : 255;
                RectF last = out.isEmpty() ? null : out.get(out.size() - 1);
                if (last != null && x0 == 0 && x1 == 255 && last.left == 0 && last.right == 256 && last.bottom == row) {
                    last.bottom += 1;
                } else {
                    out.add(new RectF(x0, row, x1 + 1, row + 1));
                }
            }
        }
        return out;
    }

    private static final String KOI7 = "ЮАБЦДЕФГХИЙКЛМНОПЯРСТУЖВЬЫЗШЭЩЧ";

    private void drawInfo(Canvas c, int layout) {
        String[] lines = describe(inspectAddr);
        tp.setTextSize(13 * dp);
        float lh = 17 * dp, pad = 8 * dp, maxW = 0;
        for (String l : lines) maxW = Math.max(maxW, tp.measureText(l));
        float bw = Math.min(maxW + 2 * pad, rect.width() - 8 * dp);
        float bh = lines.length * lh + pad * 1.4f;
        float by = toScreenY(MemTrace.posY(layout, inspectAddr));
        float bx = toScreenX(MemTrace.posX(layout, inspectAddr));
        float top = by < rect.centerY() ? rect.bottom - bh - 4 * dp : rect.top + 4 * dp;
        float left = Math.max(rect.left + 4 * dp, Math.min(bx - bw / 2f, rect.right - bw - 4 * dp));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE6101018);
        c.drawRoundRect(left, top, left + bw, top + bh, 8 * dp, 8 * dp, paint);
        for (int i = 0; i < lines.length; i++) {
            tp.setColor(i == 0 ? 0xFFFFFFFF : 0xFFC8C8D0);
            c.drawText(lines[i], left + pad, top + pad + (i + 0.8f) * lh, tp);
        }
        if (!inspectTouching) host.postInvalidateDelayed(Math.max(16, inspectUntil - System.currentTimeMillis()));
    }

    /** Human-readable description of one byte. */
    String[] describe(int a) {
        int v = machine.rawRead(a);
        String bin = String.format("%8s", Integer.toBinaryString(v)).replace(' ', '0');
        char chr = v >= 0x60 && v < 0x7F ? KOI7.charAt(v - 0x60) : (v >= 0x20 && v < 0x60 ? (char) v : '·');
        List<String> out = new ArrayList<>();
        out.add(String.format("%04X:  %02X   %s   «%c»", a, v, bin, chr));
        int sp = machine.cpu.sp;
        int ps = machine.progStart, pe = machine.progEnd;
        if (a < 0x9000 && a >= sp - 48 && a <= Math.min(sp + 64, 0x8FFF))
            out.add(String.format("стек (SP = %04X)", sp));
        if (ps >= 0 && a >= ps && a <= pe)
            out.add(String.format("программа %s (%04X–%04X)", programTitle == null ? "" : programTitle, ps, pe));
        if (a < 0x8F00) {
            if (ps < 0 || a < ps || a > pe) out.add("ОЗУ (0000–8EFF)");
        } else if (a < 0x9000) {
            String var = a == 0x8FFC ? ": Y курсора" : a == 0x8FFD ? ": X курсора" : a == 0x8FE9 ? ": последний символ" : "";
            out.add("рабочие ячейки монитора" + var);
        } else if (a < 0xC000) {
            int off = a - 0x9000;
            out.add(String.format("экран: x %d–%d, y %d", (off >> 8) * 8, (off >> 8) * 8 + 7, off & 0xFF));
        } else if (a < 0xF000) {
            if (a >= 0xC400 && a < 0xC800) {
                int code = (a - 0xC400) >> 3;
                char g = code >= 0x60 && code < 0x7F ? KOI7.charAt(code - 0x60) : (code >= 0x20 && code < 0x60 ? (char) code : '·');
                out.add(String.format("ПЗУ: шрифт, символ %02X «%c», строка %d", code, g, (a - 0xC400) & 7));
            } else if (a >= 0xC800 && a < 0xC836) {
                out.add(String.format("ПЗУ: таблица точек входа, вход C8%02X", (a - 0xC800) / 3 * 3));
            } else if (a >= 0xD800) {
                out.add("ПЗУ монитора: свободное место (FF)");
            } else {
                out.add("ПЗУ монитора (C000–D7FF)");
            }
        } else if (a < 0xF800) {
            out.add("не подключено (читается FF)");
        } else {
            String[] regs = {"порт A — столбцы клавиатуры", "порт B — строки, НР, магнитофон",
                "порт C — клавиатура, звук (бит 5)", "регистр управления"};
            out.add("ВВ55: " + regs[a & 3]);
        }
        StringBuilder acc = new StringBuilder();
        long tnow = machine.time();
        appendAgo(acc, "чтение", trace.lastRead[a], tnow);
        appendAgo(acc, "запись", trace.lastWrite[a], tnow);
        appendAgo(acc, "исполнение", trace.lastExec[a], tnow);
        out.add(acc.length() == 0 ? "обращений не было" : acc.toString());
        return out.toArray(new String[0]);
    }

    /** age of an access in emulated time (cycles of the 2 MHz processor) */
    private static void appendAgo(StringBuilder sb, String what, long stamp, long now) {
        long age = now - stamp;
        if (age < 0 || age > 1L << 40) return;
        if (sb.length() > 0) sb.append(", ");
        if (age < 100_000) sb.append(what).append(String.format(java.util.Locale.US, " %d т. назад", age));
        else sb.append(what).append(String.format(java.util.Locale.US, " %.1f с назад", age / (float) Specialist.CPU_HZ));
    }

    // ------------------------------------------------------------------ tutorial

    boolean isTutorialActive() {
        return step >= 0;
    }

    void startTutorial() {
        step = 0;
        textScroll = 0;
        focusStep();
        host.invalidate();
    }

    void stopTutorial() {
        if (step < 0) return;
        step = -1;
        resetView();
        host.invalidate();
        host.onTutorialClosed();
    }

    private void goStep(int s) {
        if (s < 0 || s >= Tutorial.STEPS.length) {
            if (s >= Tutorial.STEPS.length) stopTutorial();
            return;
        }
        step = s;
        textScroll = 0;
        focusStep();
        host.invalidate();
    }

    /** zoom the map onto the step's region */
    private void focusStep() {
        Tutorial.Step st = Tutorial.STEPS[step];
        viewLayout = layout();
        if (st.ranges == null && !st.stack) {
            zoom = 1f;
            cx = cw() / 2f;
            cy = ch() / 2f;
            return;
        }
        RectF bb = null;
        for (int[] r : stepRanges(st)) {
            for (RectF m : rangeRects(layout(), r[0], r[1])) {
                if (bb == null) bb = new RectF(m);
                else bb.union(m);
            }
        }
        if (bb == null) return;
        float base = Math.min(rect.width() / cw(), rect.height() / ch());
        float z = Math.min(rect.width() / (bb.width() * base), rect.height() / (bb.height() * base)) * 0.85f;
        zoom = Math.max(1f, Math.min(Math.min(z, 6f), maxZoom()));
        cx = bb.centerX();
        cy = bb.centerY();
    }

    private void drawPanel(Canvas c) {
        Tutorial.Step st = Tutorial.STEPS[step];
        RectF r = panelRect;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xF2141420);
        c.drawRoundRect(r, 12 * dp, 12 * dp, paint);
        float pad = 12 * dp;

        // header
        tp.setTextSize(16 * dp);
        tp.setFakeBoldText(true);
        tp.setColor(0xFFFFD040);
        String head = (step + 1) + "/" + Tutorial.STEPS.length + "  " + st.title;
        float maxHead = r.width() - 2 * pad - 36 * dp;
        while (head.length() > 4 && tp.measureText(head) > maxHead) head = head.substring(0, head.length() - 2);
        c.drawText(head, r.left + pad, r.top + pad + 14 * dp, tp);
        tp.setFakeBoldText(false);
        btnClose.set(r.right - 40 * dp, r.top + 4 * dp, r.right - 4 * dp, r.top + 36 * dp);
        tp.setTextSize(20 * dp);
        tp.setColor(0xFFB0B0C0);
        c.drawText("✕", btnClose.centerX() - tp.measureText("✕") / 2f, btnClose.centerY() + 7 * dp, tp);

        // buttons
        float bh = 40 * dp;
        btnPrev.set(r.left + pad, r.bottom - pad - bh, r.left + pad + 110 * dp, r.bottom - pad);
        btnNext.set(r.right - pad - 110 * dp, r.bottom - pad - bh, r.right - pad, r.bottom - pad);
        drawButton(c, btnPrev, "◀ Назад", step > 0);
        drawButton(c, btnNext, step == Tutorial.STEPS.length - 1 ? "Готово ✓" : "Далее ▶", true);

        // text (scrollable)
        String text = st.text.replace("%SP%", String.format("%04X", machine.cpu.sp & 0xFFFF));
        float textTop = r.top + pad + 26 * dp, textBottom = btnPrev.top - 8 * dp;
        float tw = r.width() - 2 * pad;
        if (textLayout == null || layoutStep != step || layoutWidth != tw || st.stack) {
            textPaint.setTextSize(14 * dp);
            textPaint.setColor(0xFFE0E0E8);
            textLayout = StaticLayout.Builder.obtain(text, 0, text.length(), textPaint, (int) tw)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(2 * dp, 1f)
                    .build();
            layoutStep = step;
            layoutWidth = tw;
        }
        float visible = textBottom - textTop;
        float maxScroll = Math.max(0, textLayout.getHeight() - visible);
        textScroll = Math.max(0, Math.min(maxScroll, textScroll));
        c.save();
        c.clipRect(r.left + pad, textTop, r.right - pad, textBottom);
        c.translate(r.left + pad, textTop - textScroll);
        textLayout.draw(c);
        c.restore();
        if (maxScroll > 0) {
            // scroll indicator
            float th = visible * visible / textLayout.getHeight();
            float ty = textTop + (visible - th) * (textScroll / maxScroll);
            paint.setColor(0x66FFFFFF);
            c.drawRoundRect(r.right - 5 * dp, ty, r.right - 2 * dp, ty + th, 2 * dp, 2 * dp, paint);
            if (textScroll < maxScroll - 1) {
                tp.setTextSize(12 * dp);
                tp.setColor(0xFF9090A0);
                String more = "↓ листайте текст";
                c.drawText(more, r.centerX() - tp.measureText(more) / 2f, btnPrev.centerY() + 4 * dp, tp);
            }
        }
    }

    private void drawButton(Canvas c, RectF b, String label, boolean enabled) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(enabled ? 0xFF3A5BD9 : 0xFF2A2A34);
        c.drawRoundRect(b, 10 * dp, 10 * dp, paint);
        tp.setTextSize(15 * dp);
        tp.setColor(enabled ? 0xFFFFFFFF : 0xFF707080);
        c.drawText(label, b.centerX() - tp.measureText(label) / 2f, b.centerY() + 5 * dp, tp);
    }

    boolean panelContains(float x, float y) {
        return step >= 0 && panelRect.contains(x, y);
    }

    void panelDown(int id, float x, float y) {
        panelPtr = id;
        panelLastY = y;
        panelDragged = false;
        downX = x;
        downY = y;
    }

    void panelMove(int id, float x, float y) {
        if (id != panelPtr) return;
        float dy = y - panelLastY;
        if (Math.abs(y - downY) > 8 * dp) panelDragged = true;
        if (panelDragged) {
            textScroll -= dy;
            host.invalidate();
        }
        panelLastY = y;
    }

    void panelUp(int id, float x, float y) {
        if (id != panelPtr) return;
        panelPtr = -1;
        if (panelDragged) return;
        if (btnClose.contains(x, y)) stopTutorial();
        else if (btnNext.contains(x, y)) goStep(step + 1);
        else if (btnPrev.contains(x, y) && step > 0) goStep(step - 1);
    }

    // ------------------------------------------------------------------ touch on the map

    void down(int id, float x, float y) {
        ptrs.put(id, new float[]{x, y});
        if (ptrs.size() == 2) {
            // second finger: pinch / pan
            gesture = true;
            inspectTouching = false;
            float[] a = ptrs.valueAt(0), b = ptrs.valueAt(1);
            g0Dist = Math.max(1f, dist(a, b));
            g0Zoom = zoom;
            float mx = (a[0] + b[0]) / 2f, my = (a[1] + b[1]) / 2f;
            g0Mx = toMapX(mx);
            g0My = toMapY(my);
        } else if (ptrs.size() == 1) {
            // one finger: drag the map; a tap without movement shows what is under it
            gesture = false;
            moved = false;
            downX = x;
            downY = y;
            dragMx = toMapX(x);
            dragMy = toMapY(y);
        }
        host.invalidate();
    }

    void move(int id, float x, float y) {
        float[] p = ptrs.get(id);
        if (p == null) return;
        p[0] = x;
        p[1] = y;
        if (Math.abs(x - downX) + Math.abs(y - downY) > 12 * dp) moved = true;
        if (gesture && ptrs.size() >= 2) {
            float[] a = ptrs.valueAt(0), b = ptrs.valueAt(1);
            zoom = g0Zoom * dist(a, b) / g0Dist;
            zoom = Math.max(1f, Math.min(maxZoom(), zoom));
            float mx = (a[0] + b[0]) / 2f, my = (a[1] + b[1]) / 2f;
            // keep the map point that was under the fingers under the fingers
            cx = g0Mx - (mx - rect.centerX()) / scale();
            cy = g0My - (my - rect.centerY()) / scale();
            clampView();
        } else if (!gesture && moved) {
            // keep the map point under the finger
            cx = dragMx - (x - rect.centerX()) / scale();
            cy = dragMy - (y - rect.centerY()) / scale();
            clampView();
        }
        host.invalidate();
    }

    void up(int id, float x, float y) {
        ptrs.remove(id);
        if (gesture) {
            if (ptrs.size() == 0) gesture = false;
            host.invalidate();
            return;
        }
        inspectTouching = false;
        long now = System.currentTimeMillis();
        if (!moved) {
            inspectAt(x, y);
            inspectUntil = now + 4000;
        }
        if (!moved && now - lastTapTime < 300 && Math.hypot(x - lastTapX, y - lastTapY) < 40 * dp) {
            // double tap: zoom in around the point / back to whole map
            if (zoom > 1.5f) {
                zoom = 1f;
            } else {
                float mx = toMapX(x), my = toMapY(y);
                zoom = Math.min(maxZoom(), 4f);
                cx = mx;
                cy = my;
            }
            clampView();
            lastTapTime = 0;
        } else {
            lastTapTime = moved ? 0 : now;
            lastTapX = x;
            lastTapY = y;
        }
        host.invalidate();
    }

    void cancel() {
        ptrs.clear();
        gesture = false;
        inspectTouching = false;
        panelPtr = -1;
    }

    private void inspectAt(float x, float y) {
        int a = MemTrace.addrAt(layout(), (int) Math.floor(toMapX(x)), (int) Math.floor(toMapY(y)));
        if (a >= 0) inspectAddr = a;
    }

    private static float dist(float[] a, float[] b) {
        return (float) Math.hypot(a[0] - b[0], a[1] - b[1]);
    }
}
