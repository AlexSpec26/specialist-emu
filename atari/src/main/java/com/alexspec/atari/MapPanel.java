package com.alexspec.atari;

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

import com.alexspec.atari.core.AtariMachine;
import com.alexspec.atari.core.MemTrace;

import java.util.ArrayList;
import java.util.List;

/**
 * The live memory map: two layouts, pinch zoom / two-finger pan, a tap inspector and the
 * guided tutorial overlay. Owned and laid out by {@link MainView}. Ported from the ZX app; the
 * descriptions of the addresses are those of the Atari 800XL/65XE.
 * GPL-2.0-or-later, see atari/COPYING.
 */
final class MapPanel {
    interface Host {
        void invalidate();

        void postInvalidateDelayed(long ms);

        void onTutorialClosed();
    }

    private final EmuThread emu;
    private final AtariMachine machine;
    private final MemTrace trace;
    private final Host host;
    private final float dp;

    final RectF rect = new RectF();
    /** area for the tutorial panel (set by the owner) */
    final RectF panelRect = new RectF();
    String programTitle = "";
    /** built-in BASIC switched on (A000-BFFF is ROM) */
    boolean basicOn;

    // pictures from the emulation thread
    private Bitmap bmp = Bitmap.createBitmap(MemTrace.width(1), MemTrace.MAP_H, Bitmap.Config.ARGB_8888);
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
        return Math.max(1f, 10f * dp / 2.75f / base);
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
            if (bmp.getWidth() != MemTrace.width(got))
                bmp = Bitmap.createBitmap(MemTrace.width(got), MemTrace.MAP_H, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pix, 0, MemTrace.width(got), 0, 0, MemTrace.width(got), MemTrace.height(got));
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
            c.drawBitmap(bmp, matrix, bmpPaint);
        }

        // tutorial highlight: dim everything except the step's region
        Tutorial.Step st = step >= 0 ? Tutorial.STEPS[step] : null;
        if (st != null && (st.ranges != null || st.dynamic != Tutorial.NONE)) {
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

    private int[][] stepRanges(Tutorial.Step st) {
        switch (st.dynamic) {
            case Tutorial.STACK: {
                int sp = machine.sp();
                return new int[][]{{Math.max(0x100, sp - 24), Math.min(0x1FF, sp + 24)}};
            }
            case Tutorial.BASIC: {
                int[] b = basicAreas();
                if (b == null) return new int[][]{{0x80, 0x91}};
                return new int[][]{{b[0], Math.max(b[0], b[7] - 1)}};
            }
            case Tutorial.DLIST: {
                int[] d = displayList();
                return new int[][]{{d[0], d[1]}};
            }
            case Tutorial.SCREEN: {
                List<int[]> sc = screenRanges();
                if (sc.isEmpty()) return new int[][]{{0x58, 0x59}};
                return sc.toArray(new int[0][]);
            }
            case Tutorial.FONT: {
                int base = machine.rawRead(0x2F4) << 8;
                return new int[][]{{base, Math.min(0xFFFF, base + 1023)}};
            }
            default:
                return st.ranges;
        }
    }

    private int word(int a) {
        return machine.word(a);
    }

    /**
     * BASIC memory areas from its zero-page pointers: LOMEM, VNTP, VNTD, VVTP, STMTAB, STARP,
     * RUNSTK, MEMTOP; null if BASIC is off or they do not look like a running BASIC.
     */
    int[] basicAreas() {
        if (!basicOn) return null;
        int[] v = {word(0x80), word(0x82), word(0x84), word(0x86), word(0x88), word(0x8C), word(0x8E), word(0x90)};
        if (v[0] < 0x700 || v[0] >= 0xA000) return null;
        for (int i = 1; i < v.length; i++) if (v[i] < v[i - 1] || v[i] > 0xA000) return null;
        return v;
    }

    /** ANTIC modes 0..F: bytes per line at normal width, scan lines per mode line */
    private static final int[] MODE_BYTES = {0, 0, 40, 40, 40, 40, 20, 20, 10, 10, 20, 20, 20, 40, 40, 40};
    private static final int[] MODE_LINES = {0, 0, 8, 10, 8, 16, 8, 16, 8, 4, 4, 2, 1, 2, 1, 1};

    /** display list start (SDLSTL) and its last byte, following it to the jump (at most 256 bytes) */
    int[] displayList() {
        int a = word(0x230), p = a, end = a;
        for (int n = 0; n < 256; n++) {
            int ins = machine.rawRead(p), mode = ins & 15;
            if (mode == 1) {
                end = p + 2; // JMP / JVB with its address
                break;
            }
            int len = (mode >= 2 && (ins & 0x40) != 0) ? 3 : 1;
            end = p + len - 1;
            p += len;
        }
        return new int[]{a, Math.min(0xFFFF, end)};
    }

    /** screen memory read by the display list: ranges {start, end} (one per LMS) */
    List<int[]> screenRanges() {
        List<int[]> out = new ArrayList<>();
        int width = machine.rawRead(0x22F) & 3; // SDMCTL playfield width: 1 narrow, 2 normal, 3 wide
        int scale = width == 1 ? 32 : width == 3 ? 48 : 40;
        int p = word(0x230), mem = -1, start = -1, lines = 0;
        for (int n = 0; n < 256 && lines < 260; n++) {
            int ins = machine.rawRead(p), mode = ins & 15;
            if (mode == 1) break;
            if (mode == 0) {
                lines += ((ins >> 4) & 7) + 1;
                p++;
                continue;
            }
            if ((ins & 0x40) != 0) {
                if (start >= 0 && mem > start) out.add(new int[]{start, Math.min(0xFFFF, mem - 1)});
                mem = start = word(p + 1);
                p += 3;
            } else {
                p++;
            }
            if (mem >= 0) mem += MODE_BYTES[mode] * scale / 40;
            lines += MODE_LINES[mode];
        }
        if (start >= 0 && mem > start) out.add(new int[]{start, Math.min(0xFFFF, mem - 1)});
        return out;
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

    /** map-space rectangles covering addresses a..b (columns of 256 lines, W bytes per line) */
    static List<RectF> rangeRects(int layout, int a, int b) {
        List<RectF> out = new ArrayList<>();
        int w = Math.max(1, Math.min(4, layout)), colBytes = 256 * w;
        for (int col = a / colBytes; col <= b / colBytes; col++) {
            int lo = Math.max(a, col * colBytes), hi = Math.min(b, col * colBytes + colBytes - 1);
            int x0 = col * w * 8;
            int r0 = (lo % colBytes) / w, r1 = (hi % colBytes) / w;
            int k0 = lo % w, k1 = hi % w;
            if (r0 == r1) {
                out.add(new RectF(x0 + k0 * 8, r0, x0 + (k1 + 1) * 8, r0 + 1));
                continue;
            }
            int full0 = r0, full1 = r1;
            if (k0 != 0) {
                out.add(new RectF(x0 + k0 * 8, r0, x0 + w * 8, r0 + 1));
                full0++;
            }
            if (k1 != w - 1) {
                out.add(new RectF(x0, r1, x0 + (k1 + 1) * 8, r1 + 1));
                full1--;
            }
            if (full1 >= full0) {
                RectF last = out.isEmpty() ? null : out.get(out.size() - 1);
                // merge whole columns into one rectangle
                if (last != null && full0 == 0 && full1 == 255 && last.top == 0 && last.bottom == 256 && last.right == x0)
                    last.right = x0 + w * 8;
                else out.add(new RectF(x0, full0, x0 + w * 8, full1 + 1));
            }
        }
        return out;
    }

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

    /** ATASCII character for a byte as text (ATASCII code, not screen code) */
    private static char atascii(int v) {
        v &= 0x7F;
        if (v >= 0x20 && v < 0x7B) return (char) v;
        return '·';
    }

    /** screen (internal) code → ATASCII: 00–3F = 20–5F, 40–5F = 00–1F, 60–7F = 60–7F */
    private static int internalToAtascii(int v) {
        int c = v & 0x7F;
        return c < 0x40 ? c + 0x20 : c < 0x60 ? c - 0x40 : c;
    }

    /** OS and BASIC variables: address, length, name, meaning */
    private static final Object[][] VARS = {
        {0x0008, 1, "WARMST", "0 после включения, FF после RESET"}, {0x000A, 2, "DOSVEC", "куда переходить вместо «самотеста»/DOS"},
        {0x000C, 2, "DOSINI", "инициализация DOS при RESET"}, {0x0010, 1, "POKMSK", "копия IRQEN: какие прерывания POKEY разрешены"},
        {0x0011, 1, "BRKKEY", "0 — нажата BREAK"}, {0x0012, 3, "RTCLOK", "часы: +1 каждый кадр (1/50 с), младший байт — 0014"},
        {0x0041, 1, "SOUNDR", "звук обмена с диском/магнитофоном (0 = тихо)"}, {0x0042, 1, "CRITIC", "критическая секция: отложенный VBI сокращён"},
        {0x004D, 1, "ATRACT", "счётчик простоя: >127 — «заставка», цвета меняются"},
        {0x0052, 1, "LMARGN", "левое поле текста"}, {0x0053, 1, "RMARGN", "правое поле текста"},
        {0x0054, 1, "ROWCRS", "строка курсора"}, {0x0055, 2, "COLCRS", "колонка курсора"},
        {0x0057, 1, "DINDEX", "текущий графический режим ОС (GRAPHICS n)"}, {0x0058, 2, "SAVMSC", "начало экранной памяти"},
        {0x005A, 1, "OLDROW", "предыдущая строка (для DRAWTO)"}, {0x005B, 2, "OLDCOL", "предыдущая колонка"},
        {0x005D, 1, "OLDCHR", "символ под курсором"}, {0x005E, 2, "OLDADR", "адрес курсора в экране"},
        {0x006A, 1, "RAMTOP", "верх ОЗУ в страницах по 256 байт (A0 = A000)"},
        {0x0080, 2, "LOMEM", "BASIC: начало своей памяти"}, {0x0082, 2, "VNTP", "BASIC: таблица имён переменных"},
        {0x0084, 2, "VNTD", "BASIC: конец таблицы имён"}, {0x0086, 2, "VVTP", "BASIC: таблица значений переменных"},
        {0x0088, 2, "STMTAB", "BASIC: начало программы"}, {0x008A, 2, "STMCUR", "BASIC: текущая строка"},
        {0x008C, 2, "STARP", "BASIC: массивы и строки"}, {0x008E, 2, "RUNSTK", "BASIC: стек GOSUB и FOR"},
        {0x0090, 2, "MEMTOP", "BASIC: конец занятой памяти"},
        {0x00D4, 6, "FR0", "регистр плавающей точки 0 (6 байт, двоично-десятичный)"}, {0x00E0, 6, "FR1", "регистр плавающей точки 1"},
        {0x00F3, 2, "INBUFF", "указатель на текст для перевода в число"},
        {0x0200, 2, "VDSLST", "вектор прерывания списка отображения (DLI)"}, {0x0206, 2, "VBREAK", "вектор команды BRK"},
        {0x0208, 2, "VKEYBD", "вектор прерывания клавиатуры"}, {0x0216, 2, "VIMIRQ", "вектор всех IRQ"},
        {0x0218, 10, "CDTMV1–5", "таймеры ОС, уменьшаются каждый кадр"},
        {0x0222, 2, "VVBLKI", "вектор VBI, немедленная часть"}, {0x0224, 2, "VVBLKD", "вектор VBI, отложенная часть"},
        {0x022F, 1, "SDMCTL", "копия DMACTL: экран вкл/выкл, ширина поля, игроки"},
        {0x0230, 2, "SDLSTL", "копия адреса списка отображения ANTIC"},
        {0x026F, 1, "GPRIOR", "копия PRIOR: приоритеты, режимы GTIA"},
        {0x0270, 8, "PADDL0–7", "ручки управления (пэдлы)"}, {0x0278, 4, "STICK0–3", "джойстики (15 — в центре)"},
        {0x0284, 4, "STRIG0–3", "кнопки джойстиков (0 — нажата)"},
        {0x02BE, 1, "SHFLOK", "регистр букв: 0 строчные, 40 заглавные, 80 управляющие"},
        {0x02C0, 4, "PCOLR0–3", "цвета игроков (копии для GTIA)"}, {0x02C4, 5, "COLOR0–4", "цвета поля, 02C8 — фон и рамка"},
        {0x02E5, 2, "MEMTOP", "ОС: верх свободной памяти"}, {0x02E7, 2, "MEMLO", "ОС: низ свободной памяти"},
        {0x02F0, 1, "CRSINH", "0 — курсор виден"}, {0x02F3, 1, "CHACT", "копия CHACTL: инверсия, «вверх ногами»"},
        {0x02F4, 1, "CHBAS", "страница шрифта (E0 = E000)"}, {0x02FC, 1, "CH", "код последней клавиши (FF — нет)"},
        {0x0300, 12, "DCB", "блок управления устройством для SIO (диск, магнитофон)"},
        {0x0340, 128, "IOCB", "8 блоков ввода-вывода по 16 байт: #0 — экран E:"},
        {0x03C0, 40, "PRNBUF", "буфер принтера"}, {0x03FD, 131, "CASBUF", "буфер магнитофона"},
        {0x0580, 128, "LBUFF", "строковый буфер (ввод BASIC, числа)"},
    };

    /** chip registers: base, name per offset (writes / reads) */
    private static final String[] GTIA = {
        "HPOSP0 / M0PF", "HPOSP1 / M1PF", "HPOSP2 / M2PF", "HPOSP3 / M3PF", "HPOSM0 / P0PF", "HPOSM1 / P1PF",
        "HPOSM2 / P2PF", "HPOSM3 / P3PF", "SIZEP0 / M0PL", "SIZEP1 / M1PL", "SIZEP2 / M2PL", "SIZEP3 / M3PL",
        "SIZEM / P0PL", "GRAFP0 / P1PL", "GRAFP1 / P2PL", "GRAFP2 / P3PL", "GRAFP3 / TRIG0", "GRAFM / TRIG1",
        "COLPM0 / TRIG2", "COLPM1 / TRIG3", "COLPM2 / PAL", "COLPM3", "COLPF0", "COLPF1", "COLPF2", "COLPF3",
        "COLBK", "PRIOR", "VDELAY", "GRACTL", "HITCLR", "CONSOL",
    };
    private static final String[] POKEY = {
        "AUDF1 / POT0", "AUDC1 / POT1", "AUDF2 / POT2", "AUDC2 / POT3", "AUDF3 / POT4", "AUDC3 / POT5",
        "AUDF4 / POT6", "AUDC4 / POT7", "AUDCTL / ALLPOT", "STIMER / KBCODE", "SKREST / RANDOM", "POTGO",
        "—", "SEROUT / SERIN", "IRQEN / IRQST", "SKCTL / SKSTAT",
    };
    private static final String[] PIA = {"PORTA: джойстики", "PORTB: переключение памяти XL", "PACTL", "PBCTL"};
    private static final String[] ANTIC = {
        "DMACTL", "CHACTL", "DLISTL", "DLISTH", "HSCROL", "VSCROL", "—", "PMBASE", "—", "CHBASE", "WSYNC",
        "VCOUNT", "PENH", "PENV", "NMIEN", "NMIRES / NMIST",
    };

    /** OS ROM entry points */
    private static final Object[][] ROM_ENTRIES = {
        {0xE450, "DISKIV — инициализация диска"}, {0xE453, "DSKINV — работа с диском"}, {0xE456, "CIOV — ввод-вывод (CIO)"},
        {0xE459, "SIOV — последовательный порт (SIO)"}, {0xE45C, "SETVBV — установка таймеров/векторов VBI"},
        {0xE45F, "SYSVBV — VBI системы"}, {0xE462, "XITVBV — выход из VBI"}, {0xE474, "WARMSV — тёплый старт"},
        {0xE477, "COLDSV — холодный старт"}, {0xFFFA, "вектор NMI (VBI и DLI)"}, {0xFFFC, "вектор RESET"},
        {0xFFFE, "вектор IRQ (клавиатура, таймеры, BRK)"},
    };

    /** Human-readable description of one byte. */
    String[] describe(int a) {
        int v = machine.rawRead(a);
        String bin = String.format("%8s", Integer.toBinaryString(v)).replace(' ', '0');
        List<String> out = new ArrayList<>();
        out.add(String.format("%04X:  %02X   %s   «%c»   %d", a, v, bin, atascii(v), v));
        int sp = machine.sp();
        if (a >= 0x100 && a < 0x200) {
            out.add(a > sp ? String.format("стек: занят (S = %02X), 0100 + S — первое свободное место", sp & 0xFF)
                    : String.format("стек: свободно (S = %02X)", sp & 0xFF));
        }
        for (Object[] sv : VARS) {
            int s0 = (Integer) sv[0], n = (Integer) sv[1];
            if (a >= s0 && a < s0 + n) {
                String val = n == 2 ? String.format(" = %04X", word(s0)) : n == 1 ? String.format(" = %02X", machine.rawRead(s0)) : "";
                out.add((a < 0x100 ? "нулевая страница: " : "") + sv[2] + val);
                out.add((String) sv[3]);
            }
        }
        int[] dl = displayList();
        if (a >= dl[0] && a <= dl[1]) out.add(String.format("список отображения ANTIC (%04X–%04X)", dl[0], dl[1]));
        for (int[] r : screenRanges()) {
            if (a >= r[0] && a <= r[1]) {
                out.add(String.format("экранная память (%04X–%04X); как символ: «%c»", r[0], r[1], atascii(internalToAtascii(v))));
                break;
            }
        }
        int font = machine.rawRead(0x2F4) << 8;
        if (a >= font && a < font + 1024) {
            int code = (a - font) >> 3;
            out.add(String.format("шрифт (CHBAS = %02X): символ %02X «%c», строка %d", font >> 8, code,
                    atascii(internalToAtascii(code)), a & 7));
        }
        if (a < 0x80) {
            if (out.size() == 1) out.add("нулевая страница ОС (0000–007F): быстрые переменные системы");
        } else if (a < 0x100) {
            if (out.size() == 1) out.add("нулевая страница (0080–00FF): BASIC, плавающая точка, программы");
        } else if (a < 0x200) {
            // stack: already described
        } else if (a < 0x600) {
            if (out.size() == 1) out.add(a < 0x480 ? "системные переменные и буферы ОС (0200–047F)"
                    : "свободно для программ, буфер строки (0480–05FF)");
        } else if (a < 0x700) {
            out.add("страница 6 (0600–06FF): никем не занята, место для коротких программ");
        } else if (a < 0xA000 || (a < 0xC000 && !basicOn)) {
            int[] b = basicAreas();
            String area = null;
            if (b != null) {
                String[] names = {"таблица имён переменных (VNTP)", "", "таблица значений (VVTP)", "программа на BASIC (STMTAB)",
                    "", "массивы и строки (STARP)", "стек GOSUB/FOR (RUNSTK)"};
                if (a >= b[1] && a < b[2]) area = names[0];
                else if (a >= b[3] && a < b[4]) area = names[2];
                else if (a >= b[4] && a < b[5]) area = names[3];
                else if (a >= b[5] && a < b[6]) area = names[5];
                else if (a >= b[6] && a < b[7]) area = names[6];
                else if (a >= b[7] && a < word(0x2E5)) area = "свободно (между BASIC и экраном)";
            }
            int memlo = word(0x2E7), ramtop = machine.rawRead(0x6A) << 8;
            if (area == null) {
                if (a < memlo) area = String.format("ОЗУ ниже MEMLO (%04X): DOS или программа", memlo);
                else if (a >= ramtop) area = String.format("ОЗУ выше RAMTOP (%04X)", ramtop);
                else area = "ОЗУ";
            }
            out.add(area);
        } else if (a < 0xC000) {
            out.add("BASIC в ПЗУ (A000–BFFF; без BASIC здесь ОЗУ или картридж)");
        } else if (a >= 0xD000 && a < 0xD800) {
            int o = a & 0xFF;
            String chip;
            if (a < 0xD100) chip = "GTIA, регистр " + GTIA[o & 0x1F] + " (цвета, игроки, столкновения, кнопки, CONSOL)";
            else if (a < 0xD200) chip = "шина расширения (PBI)";
            else if (a < 0xD300) chip = "POKEY, регистр " + POKEY[o & 0x0F] + " (звук, клавиатура, случайные числа, IRQ)";
            else if (a < 0xD400) chip = "PIA, " + PIA[o & 3];
            else if (a < 0xD500) chip = "ANTIC, регистр " + ANTIC[o & 0x0F] + " (видео-DMA, список отображения, NMI)";
            else if (a < 0xD600) chip = "управление картриджем (CCTL)";
            else chip = "не используется";
            out.add(chip);
            out.add("на карте здесь не значения регистров; смысл имеют только вспышки обращений");
        } else {
            String what;
            if (a >= 0xD800 && a < 0xE000) what = "ПЗУ: плавающая точка (D800–DFFF), математика BASIC";
            else if (a >= 0xE000 && a < 0xE400) {
                int code = (a - 0xE000) >> 3;
                what = String.format("ПЗУ: шрифт, символ %02X «%c», строка %d", code, atascii(internalToAtascii(code)), a & 7);
            } else what = "ПЗУ операционной системы (C000–CFFF, E400–FFFF)";
            for (Object[] e : ROM_ENTRIES) {
                int e0 = (Integer) e[0];
                int len = e0 >= 0xFFFA ? 2 : 3;
                if (a >= e0 && a < e0 + len) what = "ПЗУ: " + e[1];
            }
            out.add(what);
        }
        StringBuilder acc = new StringBuilder();
        long tnow = machine.time();
        appendAgo(acc, "чтение", trace.lastRead[a], tnow);
        appendAgo(acc, "запись", trace.lastWrite[a], tnow);
        appendAgo(acc, "исполнение", trace.lastExec[a], tnow);
        out.add(acc.length() == 0 ? "обращений не было" : acc.toString());
        return out.toArray(new String[0]);
    }

    private static void appendAgo(StringBuilder sb, String what, long stamp, long now) {
        long age = now - stamp;
        if (age < 0 || age > 1L << 40) return;
        if (sb.length() > 0) sb.append(", ");
        if (age < 100_000) sb.append(what).append(String.format(java.util.Locale.US, " %d т. назад", age));
        else sb.append(what).append(String.format(java.util.Locale.US, " %.1f с назад", age / (float) AtariMachine.CPU_HZ));
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
        if (st.ranges == null && st.dynamic == Tutorial.NONE) {
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
        int[] bas = basicAreas();
        int[] dl = displayList();
        List<int[]> sc = screenRanges();
        String text = st.text.replace("%SP%", String.format("%02X", machine.sp() & 0xFF))
                .replace("%SAVMSC%", String.format("%04X", word(0x58)))
                .replace("%DLIST%", String.format("%04X–%04X", dl[0], dl[1]))
                .replace("%SCREEN%", sc.isEmpty() ? "—" : String.format("%04X–%04X", sc.get(0)[0], sc.get(sc.size() - 1)[1]))
                .replace("%CHBAS%", String.format("%02X", machine.rawRead(0x2F4)))
                .replace("%MEMLO%", String.format("%04X", word(0x2E7)))
                .replace("%MEMTOP%", String.format("%04X", word(0x2E5)))
                .replace("%RAMTOP%", String.format("%02X", machine.rawRead(0x6A)))
                .replace("%PROG%", bas == null ? "—" : String.format("%04X", bas[4]))
                .replace("%VNTP%", bas == null ? "—" : String.format("%04X", bas[1]))
                .replace("%BASIC%", bas == null ? "Сейчас BASIC выключен или его указатели не похожи на работающий BASIC "
                        + "(программа заняла память по-своему) — подсвечены сами указатели в нулевой странице." : "");
        float textTop = r.top + pad + 26 * dp, textBottom = btnPrev.top - 8 * dp;
        float tw = r.width() - 2 * pad;
        if (textLayout == null || layoutStep != step || layoutWidth != tw || st.dynamic != Tutorial.NONE) {
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
