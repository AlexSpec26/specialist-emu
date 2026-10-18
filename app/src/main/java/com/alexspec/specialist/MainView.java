package com.alexspec.specialist;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.alexspec.specialist.core.SpecKeys;
import com.alexspec.specialist.core.Specialist;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the emulated screen and all on-screen controls (joystick, buttons, keyboard, toolbar)
 * and turns multi-touch input into Specialist key presses.
 */
final class MainView extends View {
    interface Host {
        void onMenuRequested();

        void onKeyboardToggled(boolean visible);

        void onKeyPicked(int code);

        void onPickCancelled();
    }

    private static final int T_NONE = -1;
    private static final int T_JOY = -2;
    private static final int T_MENU = -3;
    private static final int T_KBD = -4;
    private static final int T_CONSUMED = -5;
    private static final int T_BUTTON = 1000;
    private static final int T_KEY = 2000;

    private static final int DIR_UP = 1, DIR_DOWN = 2, DIR_LEFT = 4, DIR_RIGHT = 8;

    private final Specialist machine;
    private final EmuThread emu;
    private final Host host;
    private final float dp;

    // configuration (set from the activity on the UI thread)
    ControlConfig config = new ControlConfig();
    int buttonCount = 3;
    boolean joy8;
    boolean vibrate = true;
    String title = "";

    private boolean keyboardVisible;
    private String pickPrompt; // non-null while choosing a key for a control

    // geometry
    private final RectF screenRect = new RectF();
    private final RectF menuRect = new RectF();
    private final RectF kbdBtnRect = new RectF();
    private final RectF kbdRect = new RectF();
    private final RectF joyZone = new RectF();
    private final RectF btnZone = new RectF();
    private float toolbarY = -1, toolbarH;
    private float joyCx, joyCy, joyR;
    private final float[] btnX = new float[ControlConfig.MAX_BUTTONS];
    private final float[] btnY = new float[ControlConfig.MAX_BUTTONS];
    private float btnR;
    private boolean landscape;

    private static final class VKey {
        final int code;
        final float weight;
        final RectF r = new RectF();

        VKey(int code, float weight) {
            this.code = code;
            this.weight = weight;
        }
    }

    private final List<List<VKey>> rows = new ArrayList<>();

    // input state
    private final SparseIntArray pointers = new SparseIntArray();
    private int joyPointer = -1;
    private int joyDirs;
    private final int[] joyCodes = {SpecKeys.NONE, SpecKeys.NONE, SpecKeys.NONE, SpecKeys.NONE};
    private float knobX, knobY;
    private final int[] btnDown = new int[ControlConfig.MAX_BUTTONS];
    private final int[] btnCode = new int[ControlConfig.MAX_BUTTONS];
    private final int[] keyDownCount = new int[SpecKeys.COUNT];
    private boolean shiftLatched, shiftHeld, shiftUsed;

    // drawing
    private final Bitmap screenBmp = Bitmap.createBitmap(Specialist.WIDTH, Specialist.HEIGHT, Bitmap.Config.ARGB_8888);
    private final int[] pixels = new int[Specialist.WIDTH * Specialist.HEIGHT];
    private final Paint bmpPaint = new Paint();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect srcRect = new Rect(0, 0, Specialist.WIDTH, Specialist.HEIGHT);

    MainView(Context ctx, EmuThread emu, Host host) {
        super(ctx);
        this.emu = emu;
        this.machine = emu.machine;
        this.host = host;
        this.dp = ctx.getResources().getDisplayMetrics().density;
        bmpPaint.setFilterBitmap(false);
        stroke.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Color.WHITE);
        setHapticFeedbackEnabled(true);
        java.util.Arrays.fill(btnCode, SpecKeys.NONE);
        buildKeyboard();
    }

    // ------------------------------------------------------------------ keyboard layout

    private void row(Object... items) {
        List<VKey> r = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            String label = (String) items[i];
            float w = 1f;
            if (i + 1 < items.length && items[i + 1] instanceof Float) {
                w = (Float) items[++i];
            }
            int code = SpecKeys.byLabel(label);
            if (code == SpecKeys.NONE) throw new IllegalStateException("no key " + label);
            r.add(new VKey(code, w));
        }
        rows.add(r);
    }

    private void buildKeyboard() {
        row("F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "СТР");
        row(";", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-");
        row("Й", "Ц", "У", "К", "Е", "Н", "Г", "Ш", "Щ", "З", "Х", ":");
        row("Ф", "Ы", "В", "А", "П", "Р", "О", "Л", "Д", "Ж", "Э", ".");
        row("Я", "Ч", "С", "М", "И", "Т", "Ь", "Б", "Ю", ",", "/", "ЗБ");
        row("НР", 1.5f, "Р/Л", 1.25f, "АР2", 1.25f, "ТАБ", 1.25f, "Пробел", 4.25f, "ВК", 2.5f);
        row("ПС", "ПВ", "↖", "←", "↑", "↓", "→");
    }

    // ------------------------------------------------------------------ public API

    boolean isKeyboardVisible() {
        return keyboardVisible;
    }

    void setKeyboardVisible(boolean v) {
        if (keyboardVisible == v) return;
        releaseAll();
        keyboardVisible = v;
        invalidate();
    }

    /** Shows the keyboard and waits for a key tap, reported via {@link Host#onKeyPicked}. */
    void startKeyPick(String prompt) {
        releaseAll();
        pickPrompt = prompt;
        keyboardVisible = true;
        invalidate();
    }

    boolean isPicking() {
        return pickPrompt != null;
    }

    void cancelPick() {
        if (pickPrompt == null) return;
        pickPrompt = null;
        keyboardVisible = false;
        invalidate();
        host.onPickCancelled();
    }

    /** Releases every key held by on-screen controls. */
    void releaseAll() {
        pointers.clear();
        joyPointer = -1;
        setJoyDirs(0);
        for (int i = 0; i < ControlConfig.MAX_BUTTONS; i++) releaseButton(i);
        for (int c = 0; c < SpecKeys.COUNT; c++) {
            while (keyDownCount[c] > 0) {
                keyDownCount[c]--;
                machine.keyUp(c);
            }
        }
        if (shiftLatched) machine.keyUp(SpecKeys.SHIFT);
        shiftLatched = shiftHeld = shiftUsed = false;
        invalidate();
    }

    void configChanged() {
        releaseAll();
        layoutControls(getWidth(), getHeight());
        invalidate();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        releaseAll();
        layoutControls(w, h);
    }

    private void layoutControls(int w, int h) {
        if (w == 0 || h == 0) return;
        landscape = w > h;
        float tb = 52 * dp;
        toolbarH = tb;
        float small = 46 * dp;
        if (!landscape) {
            float sh = w * (float) Specialist.HEIGHT / Specialist.WIDTH;
            screenRect.set(0, 0, w, sh);
            toolbarY = sh;
            menuRect.set(8 * dp, sh + (tb - small) / 2, 8 * dp + small * 1.4f, sh + (tb + small) / 2);
            kbdBtnRect.set(w - 8 * dp - small * 1.4f, menuRect.top, w - 8 * dp, menuRect.bottom);
            float top = sh + tb;
            float regionH = h - top;
            joyZone.set(0, top, w / 2f, h);
            btnZone.set(w / 2f, top, w, h);
            float cy = top + regionH * 0.5f;
            joyCx = w * 0.27f;
            joyCy = cy;
            joyR = Math.min(w * 0.21f, regionH * 0.32f);
            layoutButtons(w * 0.73f, cy, Math.min(w * 0.2f, regionH * 0.3f));
            float rowH = Math.min(regionH / rows.size(), 64 * dp);
            kbdRect.set(0, h - rowH * rows.size() - 6 * dp, w, h);
        } else {
            float sw = Math.min(h * 1.5f, w * 0.66f);
            float sh = sw / 1.5f;
            float sx = (w - sw) / 2f;
            screenRect.set(sx, (h - sh) / 2f, sx + sw, (h + sh) / 2f);
            toolbarY = -1;
            menuRect.set(10 * dp, 10 * dp, 10 * dp + small * 1.4f, 10 * dp + small);
            kbdBtnRect.set(w - 10 * dp - small * 1.4f, 10 * dp, w - 10 * dp, 10 * dp + small);
            joyZone.set(0, small + 16 * dp, sx, h);
            btnZone.set(sx + sw, small + 16 * dp, w, h);
            float side = sx;
            joyCx = side / 2f;
            joyCy = h * 0.62f;
            joyR = Math.min(side * 0.42f, h * 0.26f);
            layoutButtons(w - side / 2f, h * 0.62f, Math.min(side * 0.42f, h * 0.26f));
            float kh = Math.min(h * 0.62f, rows.size() * 56 * dp);
            kbdRect.set(0, h - kh, w, h);
        }
        knobX = joyCx;
        knobY = joyCy;
        layoutKeys();
    }

    private void layoutButtons(float cx, float cy, float r) {
        int n = Math.max(1, Math.min(buttonCount, ControlConfig.MAX_BUTTONS));
        float[][] pos;
        switch (n) {
            case 1: pos = new float[][]{{0, 0}}; break;
            case 2: pos = new float[][]{{-0.55f, 0.3f}, {0.55f, -0.3f}}; break;
            case 3: pos = new float[][]{{-0.75f, 0.45f}, {0f, -0.05f}, {0.75f, -0.55f}}; break;
            default: pos = new float[][]{{-0.75f, 0f}, {0f, 0.75f}, {0.75f, 0f}, {0f, -0.75f}}; break;
        }
        btnR = r * (n >= 3 ? 0.4f : 0.5f);
        for (int i = 0; i < n; i++) {
            btnX[i] = cx + pos[i][0] * r;
            btnY[i] = cy + pos[i][1] * r;
        }
    }

    private void layoutKeys() {
        float pad = 3 * dp;
        float top = kbdRect.top + 6 * dp;
        float rowH = (kbdRect.bottom - top) / rows.size();
        for (int ri = 0; ri < rows.size(); ri++) {
            List<VKey> r = rows.get(ri);
            float total = 0;
            for (VKey k : r) total += k.weight;
            float x = kbdRect.left + pad;
            float unit = (kbdRect.width() - 2 * pad) / total;
            float y = top + ri * rowH;
            for (VKey k : r) {
                float kw = unit * k.weight;
                k.r.set(x + pad / 2, y + pad / 2, x + kw - pad / 2, y + rowH - pad / 2);
                x += kw;
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Color.BLACK);
        synchronized (emu.frame) {
            System.arraycopy(emu.frame, 0, pixels, 0, pixels.length);
        }
        screenBmp.setPixels(pixels, 0, Specialist.WIDTH, 0, 0, Specialist.WIDTH, Specialist.HEIGHT);
        c.drawBitmap(screenBmp, srcRect, screenRect, bmpPaint);

        if (!machine.isRomLoaded()) {
            text.setTextSize(18 * dp);
            text.setColor(0xFFAAAAAA);
            c.drawText("Нет ПЗУ — откройте меню ☰", screenRect.centerX(), screenRect.centerY(), text);
        }

        if (toolbarY >= 0) {
            fill.setColor(0xFF15151A);
            c.drawRect(0, toolbarY, getWidth(), toolbarY + toolbarH, fill);
            if (title != null && !title.isEmpty()) {
                text.setTextSize(15 * dp);
                text.setColor(0xFF9A9AA5);
                String t = title;
                float maxW = kbdBtnRect.left - menuRect.right - 16 * dp;
                while (t.length() > 3 && text.measureText(t) > maxW) t = t.substring(0, t.length() - 2);
                if (!t.equals(title)) t = t + "…";
                c.drawText(t, getWidth() / 2f, toolbarY + toolbarH / 2 + 5 * dp, text);
            }
        }
        drawUiButton(c, menuRect, "☰", false);
        drawUiButton(c, kbdBtnRect, "⌨", keyboardVisible);

        if (keyboardVisible) drawKeyboard(c);
        else drawControls(c);
    }

    private void drawUiButton(Canvas c, RectF r, String label, boolean active) {
        fill.setColor(active ? 0xCC3A5BD9 : 0x99303038);
        c.drawRoundRect(r, 10 * dp, 10 * dp, fill);
        text.setColor(Color.WHITE);
        text.setTextSize(r.height() * 0.5f);
        c.drawText(label, r.centerX(), r.centerY() + r.height() * 0.18f, text);
    }

    private void drawControls(Canvas c) {
        // joystick
        fill.setColor(0x33FFFFFF);
        c.drawCircle(joyCx, joyCy, joyR, fill);
        stroke.setColor(0x66FFFFFF);
        stroke.setStrokeWidth(2 * dp);
        c.drawCircle(joyCx, joyCy, joyR, stroke);
        text.setTextSize(joyR * 0.28f);
        drawArrow(c, "▲", joyCx, joyCy - joyR * 0.7f, (joyDirs & DIR_UP) != 0);
        drawArrow(c, "▼", joyCx, joyCy + joyR * 0.7f, (joyDirs & DIR_DOWN) != 0);
        drawArrow(c, "◀", joyCx - joyR * 0.7f, joyCy, (joyDirs & DIR_LEFT) != 0);
        drawArrow(c, "▶", joyCx + joyR * 0.7f, joyCy, (joyDirs & DIR_RIGHT) != 0);
        fill.setColor(joyPointer >= 0 ? 0xDD5A7DFF : 0x99A0A0B0);
        c.drawCircle(knobX, knobY, joyR * 0.38f, fill);

        // buttons
        int n = Math.min(buttonCount, ControlConfig.MAX_BUTTONS);
        for (int i = 0; i < n; i++) {
            boolean down = btnDown[i] > 0;
            fill.setColor(down ? 0xEE5A7DFF : 0x66FFFFFF);
            c.drawCircle(btnX[i], btnY[i], btnR, fill);
            stroke.setColor(0x99FFFFFF);
            c.drawCircle(btnX[i], btnY[i], btnR, stroke);
            String label = shortLabel(config.keys[ControlConfig.BUTTON0 + i]);
            text.setColor(Color.WHITE);
            float ts = btnR * 0.62f;
            text.setTextSize(ts);
            float tw = text.measureText(label);
            if (tw > btnR * 1.6f) text.setTextSize(ts * btnR * 1.6f / tw);
            c.drawText(label, btnX[i], btnY[i] + text.getTextSize() * 0.36f, text);
        }
    }

    private void drawArrow(Canvas c, String a, float x, float y, boolean on) {
        text.setColor(on ? 0xFFFFFFFF : 0x77FFFFFF);
        c.drawText(a, x, y + text.getTextSize() * 0.36f, text);
    }

    private static String shortLabel(int code) {
        if (code == SpecKeys.NONE) return "—";
        String l = SpecKeys.label(code);
        if ("Пробел".equals(l)) return "␣";
        return l;
    }

    private void drawKeyboard(Canvas c) {
        fill.setColor(landscape ? 0xE6101014 : 0xFF101014);
        c.drawRect(kbdRect, fill);
        if (pickPrompt != null) {
            float bh = 40 * dp;
            fill.setColor(0xF02B3F9E);
            c.drawRect(0, kbdRect.top - bh, getWidth(), kbdRect.top, fill);
            text.setColor(Color.WHITE);
            text.setTextSize(16 * dp);
            c.drawText(pickPrompt, getWidth() / 2f, kbdRect.top - bh / 2 + 6 * dp, text);
        }
        float rad = 6 * dp;
        for (List<VKey> r : rows) {
            for (VKey k : r) {
                boolean down = k.code == SpecKeys.SHIFT ? (shiftLatched || shiftHeld) : keyDownCount[k.code] > 0;
                int bg;
                if (down) bg = 0xFF5A7DFF;
                else if (k.code == SpecKeys.SHIFT || k.code == SpecKeys.ENTER || k.code == SpecKeys.LANG) bg = 0xFF3A3A48;
                else if (k.code % SpecKeys.ROWS == 5) bg = 0xFF33302A; // function keys
                else bg = 0xFF2A2A31;
                fill.setColor(bg);
                c.drawRoundRect(k.r, rad, rad, fill);
                String main = SpecKeys.label(k.code);
                String sec = SpecKeys.label2(k.code);
                text.setColor(Color.WHITE);
                float ts = Math.min(k.r.height() * 0.42f, 20 * dp);
                text.setTextSize(ts);
                float tw = text.measureText(main);
                if (tw > k.r.width() * 0.86f) text.setTextSize(ts * k.r.width() * 0.86f / tw);
                c.drawText(main, k.r.centerX(), k.r.centerY() + text.getTextSize() * 0.42f, text);
                if (!sec.isEmpty()) {
                    text.setColor(0xFF9090A0);
                    text.setTextSize(Math.min(k.r.height() * 0.26f, 12 * dp));
                    text.setTextAlign(Paint.Align.LEFT);
                    c.drawText(sec, k.r.left + 3 * dp, k.r.top + text.getTextSize() + 1 * dp, text);
                    text.setTextAlign(Paint.Align.CENTER);
                }
            }
        }
    }

    // ------------------------------------------------------------------ touch

    private int hitTest(float x, float y) {
        if (inflate(menuRect, 8 * dp).contains(x, y)) return T_MENU;
        if (inflate(kbdBtnRect, 8 * dp).contains(x, y)) return T_KBD;
        if (keyboardVisible) {
            if (kbdRect.contains(x, y)) {
                VKey best = null;
                float bestD = Float.MAX_VALUE;
                for (List<VKey> r : rows) {
                    for (VKey k : r) {
                        if (k.r.contains(x, y)) return T_KEY + k.code;
                        float dx = Math.max(Math.max(k.r.left - x, 0), x - k.r.right);
                        float dy = Math.max(Math.max(k.r.top - y, 0), y - k.r.bottom);
                        float d = dx * dx + dy * dy;
                        if (d < bestD) {
                            bestD = d;
                            best = k;
                        }
                    }
                }
                if (best != null && bestD < 100 * dp * dp) return T_KEY + best.code;
            }
            return T_NONE;
        }
        int b = hitButton(x, y, 1.35f);
        if (b >= 0) return T_BUTTON + b;
        if (joyZone.contains(x, y)) return T_JOY;
        return T_NONE;
    }

    private int hitButton(float x, float y, float slack) {
        if (!btnZone.contains(x, y)) return -1;
        int n = Math.min(buttonCount, ControlConfig.MAX_BUTTONS);
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float dx = x - btnX[i], dy = y - btnY[i];
            float d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        float lim = btnR * slack;
        return (best >= 0 && bestD <= lim * lim) ? best : -1;
    }

    private static RectF inflate(RectF r, float d) {
        return new RectF(r.left - d, r.top - d, r.right + d, r.bottom + d);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = ev.getActionIndex();
                int id = ev.getPointerId(idx);
                float x = ev.getX(idx), y = ev.getY(idx);
                int t = hitTest(x, y);
                pointers.put(id, t);
                press(id, t, x, y);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < ev.getPointerCount(); i++) {
                    int id = ev.getPointerId(i);
                    int t = pointers.get(id, T_NONE);
                    float x = ev.getX(i), y = ev.getY(i);
                    if (t == T_JOY && id == joyPointer) {
                        updateJoy(x, y);
                    } else if (t >= T_BUTTON && t < T_KEY) {
                        int nb = hitButton(x, y, 1.15f);
                        if (nb >= 0 && nb != t - T_BUTTON) {
                            releaseButton(t - T_BUTTON);
                            pointers.put(id, T_BUTTON + nb);
                            pressButton(nb);
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int idx = ev.getActionIndex();
                int id = ev.getPointerId(idx);
                int t = pointers.get(id, T_NONE);
                pointers.delete(id);
                release(id, t);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
            default:
                break;
        }
        return true;
    }

    private void haptic() {
        if (vibrate) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void press(int id, int t, float x, float y) {
        if (t == T_MENU || t == T_KBD) {
            haptic();
            return;
        }
        if (t == T_JOY) {
            if (joyPointer >= 0) {
                pointers.put(id, T_NONE);
                return;
            }
            joyPointer = id;
            updateJoy(x, y);
            return;
        }
        if (t >= T_BUTTON && t < T_KEY) {
            pressButton(t - T_BUTTON);
            return;
        }
        if (t >= T_KEY) {
            int code = t - T_KEY;
            haptic();
            if (pickPrompt != null) {
                pickPrompt = null;
                keyboardVisible = false;
                pointers.put(id, T_CONSUMED);
                invalidate();
                host.onKeyPicked(code);
                return;
            }
            if (code == SpecKeys.SHIFT) {
                if (shiftLatched) {
                    shiftLatched = false;
                    machine.keyUp(SpecKeys.SHIFT);
                    pointers.put(id, T_CONSUMED);
                } else {
                    shiftLatched = true;
                    shiftHeld = true;
                    shiftUsed = false;
                    machine.keyDown(SpecKeys.SHIFT);
                }
            } else {
                keyDownCount[code]++;
                machine.keyDown(code);
                if (shiftLatched) shiftUsed = true;
            }
            invalidate();
        }
    }

    private void release(int id, int t) {
        if (t == T_MENU) {
            host.onMenuRequested();
            return;
        }
        if (t == T_KBD) {
            if (pickPrompt != null) {
                cancelPick();
                return;
            }
            setKeyboardVisible(!keyboardVisible);
            host.onKeyboardToggled(keyboardVisible);
            return;
        }
        if (t == T_JOY && id == joyPointer) {
            joyPointer = -1;
            knobX = joyCx;
            knobY = joyCy;
            setJoyDirs(0);
            invalidate();
            return;
        }
        if (t >= T_BUTTON && t < T_KEY) {
            releaseButton(t - T_BUTTON);
            return;
        }
        if (t >= T_KEY) {
            int code = t - T_KEY;
            if (code == SpecKeys.SHIFT) {
                shiftHeld = false;
                if (shiftUsed && shiftLatched) {
                    shiftLatched = false;
                    machine.keyUp(SpecKeys.SHIFT);
                }
            } else if (keyDownCount[code] > 0) {
                keyDownCount[code]--;
                machine.keyUp(code);
                if (shiftLatched && !shiftHeld) {
                    shiftLatched = false;
                    machine.keyUp(SpecKeys.SHIFT);
                }
            }
            invalidate();
        }
    }

    private void pressButton(int i) {
        if (btnDown[i]++ == 0) {
            btnCode[i] = config.keys[ControlConfig.BUTTON0 + i];
            machine.keyDown(btnCode[i]);
            haptic();
        }
        invalidate();
    }

    private void releaseButton(int i) {
        if (btnDown[i] > 0 && --btnDown[i] == 0) {
            machine.keyUp(btnCode[i]);
            btnCode[i] = SpecKeys.NONE;
        }
        invalidate();
    }

    private void updateJoy(float x, float y) {
        float dx = x - joyCx, dy = y - joyCy;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float lim = joyR * 0.62f;
        if (dist > lim) {
            knobX = joyCx + dx / dist * lim;
            knobY = joyCy + dy / dist * lim;
        } else {
            knobX = x;
            knobY = y;
        }
        int dirs = 0;
        if (dist > joyR * 0.22f) {
            if (joy8) {
                double ang = Math.atan2(dy, dx);
                int sector = (int) Math.round(ang / (Math.PI / 4));
                sector = (sector + 8) % 8;
                switch (sector) {
                    case 0: dirs = DIR_RIGHT; break;
                    case 1: dirs = DIR_RIGHT | DIR_DOWN; break;
                    case 2: dirs = DIR_DOWN; break;
                    case 3: dirs = DIR_DOWN | DIR_LEFT; break;
                    case 4: dirs = DIR_LEFT; break;
                    case 5: dirs = DIR_LEFT | DIR_UP; break;
                    case 6: dirs = DIR_UP; break;
                    default: dirs = DIR_UP | DIR_RIGHT; break;
                }
            } else if (Math.abs(dx) > Math.abs(dy)) {
                dirs = dx > 0 ? DIR_RIGHT : DIR_LEFT;
            } else {
                dirs = dy > 0 ? DIR_DOWN : DIR_UP;
            }
        }
        setJoyDirs(dirs);
        invalidate();
    }

    private void setJoyDirs(int dirs) {
        if (dirs == joyDirs) return;
        int[] bits = {DIR_UP, DIR_DOWN, DIR_LEFT, DIR_RIGHT};
        // release first, then press, so a change of direction never leaves both held
        for (int i = 0; i < 4; i++) {
            if ((joyDirs & bits[i]) != 0 && (dirs & bits[i]) == 0) {
                machine.keyUp(joyCodes[i]);
                joyCodes[i] = SpecKeys.NONE;
            }
        }
        boolean pressed = false;
        for (int i = 0; i < 4; i++) {
            if ((joyDirs & bits[i]) == 0 && (dirs & bits[i]) != 0) {
                joyCodes[i] = config.keys[ControlConfig.UP + i];
                machine.keyDown(joyCodes[i]);
                pressed = true;
            }
        }
        if (pressed && joyDirs == 0) haptic();
        joyDirs = dirs;
    }

    // ------------------------------------------------------------------ external controllers

    /** Presses / releases a control slot from a physical gamepad. */
    void slotKey(int slot, boolean down) {
        int code = config.keys[slot];
        if (code == SpecKeys.NONE) return;
        if (down) machine.keyDown(code);
        else machine.keyUp(code);
    }
}
