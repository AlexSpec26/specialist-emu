package com.alexspec.zx;

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

import com.alexspec.zx.core.MemTrace;
import com.alexspec.zx.core.Spectrum48;

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
    private final Spectrum48 machine;
    private final MemTrace trace;
    private final Host host;
    private final float dp;

    final RectF rect = new RectF();
    /** area for the tutorial panel (set by the owner) */
    final RectF panelRect = new RectF();
    String programTitle = "";

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
        if (st.dynamic == Tutorial.STACK) {
            int sp = machine.cpu.sp & 0xFFFF;
            return new int[][]{{Math.max(0, sp - 32), Math.min(0xFFFF, sp + 32)}};
        }
        if (st.dynamic == Tutorial.BASIC) {
            int[] b = basicAreas();
            if (b == null) return new int[][]{{0x5CB6, 0x5CCF}};
            return new int[][]{{b[0], Math.max(b[0], b[6] - 1)}};
        }
        return st.ranges;
    }

    private int word(int a) {
        return machine.rawRead(a) | (machine.rawRead(a + 1) << 8);
    }

    /**
     * BASIC memory areas from the system variables: CHANS, PROG, VARS, E LINE, WORKSP, STKBOT,
     * STKEND, RAMTOP, UDG; null if they do not look like a running BASIC (a game took over).
     */
    int[] basicAreas() {
        int[] v = {word(0x5C4F), word(0x5C53), word(0x5C4B), word(0x5C59), word(0x5C61), word(0x5C63),
            word(0x5C65), word(0x5CB2), word(0x5C7B)};
        if (v[0] != 0x5CB6) return null;
        for (int i = 1; i < 7; i++) if (v[i] < v[i - 1]) return null;
        if (v[7] < v[6] || v[7] > 0xFFFF) return null;
        return v;
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

    private static char zxChar(int v) {
        if (v == 0x60) return '£';
        if (v == 0x7F) return '©';
        if (v >= 0x20 && v < 0x80) return (char) v;
        if (v >= 0x80 && v < 0x90) return '▚';
        return '·';
    }

    private static final String[] COLOR = {"чёрный", "синий", "красный", "пурпурный", "зелёный", "голубой", "жёлтый", "белый"};

    /** system variables of the 48K ROM: address, length, name, meaning */
    private static final Object[][] SYSVARS = {
        {0x5C00, 8, "KSTATE", "состояние клавиатуры"}, {0x5C08, 1, "LAST K", "код последней нажатой клавиши"},
        {0x5C09, 1, "REPDEL", "задержка автоповтора"}, {0x5C0A, 1, "REPPER", "период автоповтора"},
        {0x5C0B, 2, "DEFADD", "аргументы DEF FN"}, {0x5C0D, 1, "K DATA", "цвет при вводе"},
        {0x5C0E, 2, "TVDATA", "управляющие коды вывода"}, {0x5C10, 38, "STRMS", "потоки #0–#15"},
        {0x5C36, 2, "CHARS", "адрес шрифта минус 256 (3C00)"}, {0x5C38, 1, "RASP", "длина «гудка» ошибки"},
        {0x5C39, 1, "PIP", "длина щелчка клавиши"}, {0x5C3A, 1, "ERR NR", "код ошибки минус 1"},
        {0x5C3B, 1, "FLAGS", "флаги BASIC"}, {0x5C3C, 1, "TV FLAG", "флаги экрана"},
        {0x5C3D, 2, "ERR SP", "стек для обработки ошибок"}, {0x5C3F, 2, "LIST SP", "возврат из LIST"},
        {0x5C41, 1, "MODE", "режим курсора K/L/C/E/G"}, {0x5C42, 2, "NEWPPC", "строка для перехода"},
        {0x5C44, 1, "NSPPC", "оператор для перехода"}, {0x5C45, 2, "PPC", "номер текущей строки"},
        {0x5C47, 1, "SUBPPC", "номер оператора в строке"}, {0x5C48, 1, "BORDCR", "цвет бордюра × 8 + атрибуты нижних строк"},
        {0x5C49, 2, "E PPC", "текущая строка в листинге"}, {0x5C4B, 2, "VARS", "начало переменных"},
        {0x5C4D, 2, "DEST", "адрес переменной при присваивании"}, {0x5C4F, 2, "CHANS", "начало таблицы каналов"},
        {0x5C51, 2, "CURCHL", "текущий канал"}, {0x5C53, 2, "PROG", "начало программы на BASIC"},
        {0x5C55, 2, "NXTLIN", "следующая строка программы"}, {0x5C57, 2, "DATADD", "указатель DATA"},
        {0x5C59, 2, "E LINE", "редактируемая строка"}, {0x5C5B, 2, "K CUR", "позиция курсора"},
        {0x5C5D, 2, "CH ADD", "следующий разбираемый символ"}, {0x5C5F, 2, "X PTR", "место синтаксической ошибки"},
        {0x5C61, 2, "WORKSP", "рабочая область"}, {0x5C63, 2, "STKBOT", "дно стека калькулятора"},
        {0x5C65, 2, "STKEND", "верх стека калькулятора"}, {0x5C67, 1, "BREG", "регистр B калькулятора"},
        {0x5C68, 2, "MEM", "память калькулятора"}, {0x5C6A, 1, "FLAGS2", "ещё флаги"},
        {0x5C6B, 1, "DF SZ", "строк в нижней части экрана"}, {0x5C6C, 2, "S TOP", "верхняя строка листинга"},
        {0x5C6E, 2, "OLDPPC", "строка для CONTINUE"}, {0x5C70, 1, "OSPCC", "оператор для CONTINUE"},
        {0x5C71, 1, "FLAGX", "флаги ввода"}, {0x5C72, 2, "STRLEN", "длина строки при присваивании"},
        {0x5C74, 2, "T ADDR", "следующий элемент синтаксиса"}, {0x5C76, 2, "SEED", "зерно RND"},
        {0x5C78, 3, "FRAMES", "счётчик кадров, +1 каждые 1/50 с"}, {0x5C7B, 2, "UDG", "адрес графики пользователя"},
        {0x5C7D, 2, "COORDS", "последняя точка PLOT"}, {0x5C7F, 1, "P POSN", "колонка принтера"},
        {0x5C80, 2, "PR CC", "позиция в буфере принтера"}, {0x5C82, 2, "ECHO E", "позиция ввода"},
        {0x5C84, 2, "DF CC", "адрес вывода PRINT в экране"}, {0x5C86, 2, "DF CCL", "то же для нижних строк"},
        {0x5C88, 2, "S POSN", "колонка и строка PRINT"}, {0x5C8A, 2, "SPOSNL", "то же для нижних строк"},
        {0x5C8C, 1, "SCR CT", "сколько строк прокрутить без вопроса «scroll?»"}, {0x5C8D, 1, "ATTR P", "постоянные цвета"},
        {0x5C8E, 1, "MASK P", "маска прозрачности цветов"}, {0x5C8F, 1, "ATTR T", "временные цвета"},
        {0x5C90, 1, "MASK T", "временная маска"}, {0x5C91, 1, "P FLAG", "флаги печати (OVER, INVERSE)"},
        {0x5C92, 30, "MEMBOT", "память калькулятора"}, {0x5CB0, 2, "NMIADD", "не используется"},
        {0x5CB2, 2, "RAMTOP", "верх области BASIC (CLEAR)"}, {0x5CB4, 2, "P RAMT", "последний байт ОЗУ"},
    };

    private static final int[][] ROM_ENTRIES = {
        {0x0000, 0x0007}, {0x0008, 0x000F}, {0x0010, 0x0017}, {0x0018, 0x001F}, {0x0020, 0x0027},
        {0x0028, 0x002F}, {0x0030, 0x0037}, {0x0038, 0x0065}, {0x0066, 0x0073}, {0x028E, 0x02BE},
        {0x03B5, 0x03F7}, {0x04C2, 0x053E}, {0x053F, 0x0555}, {0x0556, 0x0604}, {0x0D6B, 0x0DAE},
        {0x11CB, 0x12A1}, {0x3D00, 0x3FFF},
    };
    private static final String[] ROM_NAMES = {
        "RST 00: START — включение / сброс", "RST 08: ERROR — сообщение об ошибке",
        "RST 10: PRINT-A — вывод символа", "RST 18: GET-CHAR — текущий символ BASIC",
        "RST 20: NEXT-CHAR — следующий символ", "RST 28: калькулятор (FP-CALC)",
        "RST 30: BC-SPACES — место в рабочей области", "RST 38: прерывание 50 Гц — опрос клавиатуры, FRAMES",
        "NMI (0066)", "KEY-SCAN — опрос клавиатуры", "BEEPER — звук", "SA-BYTES — запись на магнитофон",
        "LD-RET — конец загрузки, бордюр на место", "LD-BYTES — загрузка с магнитофона",
        "CLS — очистка экрана", "START/NEW — проверка и очистка памяти", "шрифт",
    };

    /** Human-readable description of one byte. */
    String[] describe(int a) {
        int v = machine.rawRead(a);
        String bin = String.format("%8s", Integer.toBinaryString(v)).replace(' ', '0');
        List<String> out = new ArrayList<>();
        out.add(String.format("%04X:  %02X   %s   «%c»   %d", a, v, bin, zxChar(v), v));
        int sp = machine.cpu.sp;
        int ps = machine.progStart, pe = machine.progEnd;
        if (a >= 0x4000 && a >= sp - 48 && a <= Math.min(sp + 64, 0xFFFF))
            out.add(String.format("стек (SP = %04X)", sp));
        if (ps >= 0 && a >= ps && a <= pe)
            out.add(String.format("загружено с ленты: %s (%04X–%04X)", programTitle == null ? "" : programTitle, ps, pe));
        if (a < 0x4000) {
            String what = "ПЗУ: BASIC и операционная система (0000–3FFF)";
            for (int i = 0; i < ROM_ENTRIES.length; i++) {
                if (a >= ROM_ENTRIES[i][0] && a <= ROM_ENTRIES[i][1]) {
                    what = "ПЗУ: " + ROM_NAMES[i];
                    if (a >= 0x3D00) {
                        int code = 0x20 + ((a - 0x3D00) >> 3);
                        what = String.format("ПЗУ: шрифт, символ %02X «%c», строка %d", code, zxChar(code), a & 7);
                    }
                }
            }
            out.add(what);
        } else if (a < 0x5800) {
            int third = (a >> 11) & 3, row = (a >> 5) & 7, line = (a >> 8) & 7;
            int y = third * 64 + row * 8 + line, x = (a & 31) * 8;
            out.add(String.format("экран: x %d–%d, y %d (треть %d, знакоместо %d, линия %d)", x, x + 7, y, third + 1, row, line));
        } else if (a < 0x5B00) {
            int r = (a - 0x5800) >> 5, c = a & 31;
            out.add(String.format("атрибут знакоместа: строка %d, столбец %d", r, c));
            out.add(String.format("чернила %s, бумага %s%s%s", COLOR[v & 7], COLOR[(v >> 3) & 7],
                    (v & 0x40) != 0 ? ", ярко" : "", (v & 0x80) != 0 ? ", мигание" : ""));
        } else if (a < 0x5C00) {
            out.add("буфер принтера (5B00–5BFF)");
        } else if (a < 0x5CB6) {
            for (Object[] sv : SYSVARS) {
                int s0 = (Integer) sv[0], n = (Integer) sv[1];
                if (a >= s0 && a < s0 + n) {
                    String val = n == 2 ? String.format(" = %04X", word(s0)) : n == 1 ? String.format(" = %02X", machine.rawRead(s0)) : "";
                    out.add("системная переменная " + sv[2] + val);
                    out.add((String) sv[3]);
                }
            }
        } else {
            int[] b = basicAreas();
            String area = null;
            if (b != null) {
                String[] names = {"таблица каналов (CHANS)", "программа на BASIC (PROG)", "переменные BASIC (VARS)",
                    "редактируемая строка (E LINE)", "рабочая область (WORKSP)", "стек калькулятора (STKBOT)"};
                for (int i = 0; i < 6; i++) if (a >= b[i] && a < b[i + 1]) area = names[i];
                if (area == null && a >= b[6] && a < sp) area = "свободно (между STKEND и стеком)";
                if (area == null && a > b[7] && a >= b[8]) area = String.format("графика пользователя UDG, символ %c", (char) ('A' + Math.min(20, (a - b[8]) / 8)));
                else if (area == null && a > b[7]) area = String.format("выше RAMTOP (%04X): машинный код, UDG", b[7]);
            }
            if (area == null) area = a < 0x8000 ? "ОЗУ 4000–7FFF (тормозится ULA)" : "ОЗУ 8000–FFFF (быстрая)";
            out.add(area);
            if (a < 0x8000 && b != null) out.add("ОЗУ 4000–7FFF: доступ притормаживает ULA");
        }
        StringBuilder acc = new StringBuilder();
        appendAgo(acc, "чтение", trace.lastRead[a], trace.now);
        appendAgo(acc, "запись", trace.lastWrite[a], trace.now);
        appendAgo(acc, "исполнение", trace.lastExec[a], trace.now);
        out.add(acc.length() == 0 ? "обращений не было" : acc.toString());
        return out.toArray(new String[0]);
    }

    private static void appendAgo(StringBuilder sb, String what, int stamp, int now) {
        if (stamp == 0) return;
        float sec = (now - stamp) / 50f;
        if (sb.length() > 0) sb.append(", ");
        if (sec < 0.05f) sb.append(what).append(" сейчас");
        else sb.append(what).append(String.format(java.util.Locale.US, " %.1f с назад", sec));
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
        String text = st.text.replace("%SP%", String.format("%04X", machine.cpu.sp & 0xFFFF));
        int[] bas = basicAreas();
        text = text.replace("%PROG%", bas == null ? "—" : String.format("%04X", bas[1]))
                .replace("%VARS%", bas == null ? "—" : String.format("%04X", bas[2]))
                .replace("%RAMTOP%", bas == null ? "—" : String.format("%04X", bas[7]))
                .replace("%BASIC%", bas == null ? "Сейчас системные переменные не похожи на работающий BASIC — "
                        + "видимо, игра заняла память по-своему; подсвечено начало области." : "");
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
