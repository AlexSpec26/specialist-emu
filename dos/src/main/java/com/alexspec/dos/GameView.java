package com.alexspec.dos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import java.util.ArrayList;
import java.util.List;

/**
 * The game screen: the DOS picture, the buttons (menu, pause, rewind 1 s / 5 s, speed, quick
 * save / load) and the controls — a PC keyboard or a game pad (arrows + Ctrl, Alt, Space,
 * Enter). The picture works as a touch pad for the mouse: drag moves, tap clicks, two-finger
 * tap is the right button. Nothing is drawn over the picture but the pause sign.
 *
 * Portrait: toolbar, picture, button row, controls. Landscape: the picture as high as the
 * screen, buttons and game pad in the side margins, the keyboard over the lower part on demand.
 */
final class GameView extends View {
    interface Host {
        void onMenu();

        void onPauseToggle();

        void onRewind(double seconds);

        void onSpeed();

        void onQuickSave();

        void onQuickLoad();

        void onControlsToggle();

        void onKey(int code, boolean down);

        void onPointer(int x, int y, boolean pressed, int count);
    }

    static final int CONTROLS_PAD = 0, CONTROLS_KEYBOARD = 1;

    private static final int B_MENU = 1, B_PAUSE = 2, B_BACK1 = 3, B_BACK5 = 4, B_SPEED = 5, B_SAVE = 6,
            B_LOAD = 7, B_CONTROLS = 8;
    /** game pad buttons: label and key */
    private static final String[] PAD_LABELS = {"Пробел", "Ctrl", "Alt", "Enter", "Esc"};
    private static final int[] PAD_KEYS = {PcKeys.SPACE, PcKeys.LCTRL, PcKeys.LALT, PcKeys.ENTER, PcKeys.ESC};

    private final float dp;
    private final Host host;
    private EmuThread emu;
    private Bitmap bitmap;
    private int[] pixels = new int[0];

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private final RectF screen = new RectF();
    private final RectF toolbar = new RectF();
    private final RectF controls = new RectF();
    private final List<Btn> buttons = new ArrayList<>();
    private final List<KeyCap> keys = new ArrayList<>();
    private final RectF[] padButtons = new RectF[PAD_KEYS.length];
    private float padCx, padCy, padR;
    private boolean landscape;
    private float insL, insT, insR, insB;

    int controlsMode = CONTROLS_PAD;
    boolean keyboardShown; // landscape: keyboard over the picture
    boolean paused;
    String title = "";
    String speedLabel = "1×";
    /** a short note in the toolbar (e.g. how far a rewind went), until noteUntil */
    private String note;
    private long noteUntil;

    private static final class Btn {
        final int id;
        final RectF r = new RectF();
        String label;

        Btn(int id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private static final class KeyCap {
        final PcKeys.Key key;
        final RectF r = new RectF();

        KeyCap(PcKeys.Key key) {
            this.key = key;
        }
    }

    /** what each finger holds: T_* or a key code (≥ T_KEY) */
    private static final int T_NONE = 0, T_SCREEN = 1, T_PAD = 2, T_BUTTON = 3, T_KEY = 1000;
    private final SparseArray<Integer> fingers = new SparseArray<>();
    private final int[] held = new int[512]; // how many sources hold each key
    private final int[] padDir = new int[2]; // keys held by the pad's direction
    private int screenFinger = -1;

    GameView(Context c, Host host) {
        super(c);
        this.host = host;
        dp = c.getResources().getDisplayMetrics().density;
        text.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < padButtons.length; i++) padButtons[i] = new RectF();
        for (PcKeys.Key[] row : PcKeys.ROWS) for (PcKeys.Key k : row) keys.add(new KeyCap(k));
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    void attach(EmuThread emu) {
        this.emu = emu;
    }

    void showNote(String s) {
        note = s;
        noteUntil = System.currentTimeMillis() + 1500;
        invalidate();
    }

    // ------------------------------------------------------------------ layout

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets in) {
        float l = 0, t = 0, r = 0, b = 0;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            l = s.left;
            t = s.top;
            r = s.right;
            b = s.bottom;
        } else {
            l = in.getSystemWindowInsetLeft();
            t = in.getSystemWindowInsetTop();
            r = in.getSystemWindowInsetRight();
            b = in.getSystemWindowInsetBottom();
        }
        if (l != insL || t != insT || r != insR || b != insB) {
            insL = l;
            insT = t;
            insR = r;
            insB = b;
            layout(getWidth(), getHeight());
            invalidate();
        }
        return in;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        layout(w, h);
    }

    void relayout() {
        layout(getWidth(), getHeight());
        invalidate();
    }

    private double aspect() {
        return emu != null && emu.aspect > 0.5 ? emu.aspect : 4.0 / 3.0;
    }

    private Btn button(int id, String label) {
        Btn b = new Btn(id, label);
        buttons.add(b);
        return b;
    }

    private void layout(int fullW, int fullH) {
        if (fullW == 0 || fullH == 0) return;
        float ox = insL, oy = insT, w = fullW - insL - insR, h = fullH - insT - insB;
        landscape = w > h;
        buttons.clear();
        double asp = aspect();
        float gap = 6 * dp;
        if (!landscape) {
            float tb = 48 * dp;
            toolbar.set(ox, oy, ox + w, oy + tb);
            button(B_MENU, "☰").r.set(ox + gap, oy + gap, ox + gap + 44 * dp, oy + tb - gap);
            float rx = ox + w - gap;
            button(B_CONTROLS, controlsMode == CONTROLS_PAD ? "⌨" : "🎮").r.set(rx - 44 * dp, oy + gap, rx, oy + tb - gap);
            rx -= 44 * dp + gap;
            button(B_PAUSE, paused ? "▶" : "⏸").r.set(rx - 44 * dp, oy + gap, rx, oy + tb - gap);
            float sh = (float) (w / asp);
            screen.set(ox, oy + tb, ox + w, oy + tb + sh);
            // the button row under the picture
            float rowTop = screen.bottom + gap, rowH = 44 * dp;
            int[] ids = {B_BACK1, B_BACK5, B_SPEED, B_SAVE, B_LOAD};
            String[] labels = {"⟲ 1 с", "⟲ 5 с", speedLabel, "💾", "📂"};
            float bw = (w - gap * (ids.length + 1)) / ids.length;
            for (int i = 0; i < ids.length; i++) {
                float x = ox + gap + i * (bw + gap);
                button(ids[i], labels[i]).r.set(x, rowTop, x + bw, rowTop + rowH);
            }
            controls.set(ox, rowTop + rowH + gap, ox + w, oy + h);
        } else {
            toolbar.setEmpty();
            float sw = (float) Math.min(w - 2 * 88 * dp, h * asp);
            float sh = (float) (sw / asp);
            screen.set(ox + (w - sw) / 2, oy + (h - sh) / 2, ox + (w + sw) / 2, oy + (h + sh) / 2);
            float side = screen.left - ox;
            float bw = Math.min(side - 2 * gap, 72 * dp), bh = 40 * dp;
            float lx = ox + gap, rx = screen.right + gap;
            int[] leftIds = {B_MENU, B_BACK1, B_BACK5};
            String[] leftLabels = {"☰", "⟲ 1 с", "⟲ 5 с"};
            for (int i = 0; i < leftIds.length; i++) {
                float y = oy + gap + i * (bh + gap);
                button(leftIds[i], leftLabels[i]).r.set(lx, y, lx + bw, y + bh);
            }
            int[] rightIds = {B_PAUSE, B_SPEED, B_SAVE, B_LOAD, B_CONTROLS};
            String[] rightLabels = {paused ? "▶" : "⏸", speedLabel, "💾", "📂", keyboardShown ? "🎮" : "⌨"};
            for (int i = 0; i < rightIds.length; i++) {
                float y = oy + gap + i * (bh + gap);
                float x = ox + w - gap - bw;
                button(rightIds[i], rightLabels[i]).r.set(x, y, x + bw, y + bh);
            }
            // the keyboard (on demand) over the lower part, the pad in the margins otherwise
            float kh = Math.min(h * 0.55f, 6 * 46 * dp);
            controls.set(ox, oy + h - kh, ox + w, oy + h);
        }
        layoutControls(ox, oy, w, h);
    }

    private boolean keyboardVisible() {
        return landscape ? keyboardShown : controlsMode == CONTROLS_KEYBOARD;
    }

    private boolean padVisible() {
        return landscape ? !keyboardShown : controlsMode == CONTROLS_PAD;
    }

    private void layoutControls(float ox, float oy, float w, float h) {
        // keyboard: 6 rows of 15 units
        float pad = 3 * dp;
        float unit = (controls.width() - 2 * pad) / 15f;
        float rowH = Math.min((controls.height() - 2 * pad) / 6f, unit * 1.6f);
        float top = controls.bottom - pad - rowH * 6;
        int i = 0;
        for (int r = 0; r < PcKeys.ROWS.length; r++) {
            float x = controls.left + pad;
            for (PcKeys.Key k : PcKeys.ROWS[r]) {
                KeyCap kc = keys.get(i++);
                kc.r.set(x + 1.5f * dp, top + r * rowH + 1.5f * dp, x + k.w * unit - 1.5f * dp, top + (r + 1) * rowH - 1.5f * dp);
                x += k.w * unit;
            }
        }
        // game pad: cross on the left, buttons on the right
        float bs;
        if (!landscape) {
            float area = Math.min(controls.height(), w / 2);
            padR = area * 0.42f;
            padCx = controls.left + 16 * dp + padR;
            padCy = controls.centerY();
            bs = Math.min(64 * dp, (w / 2 - 24 * dp) / 2.6f);
            float cx = controls.right - 16 * dp - bs * 1.3f, cy = controls.centerY();
            place(cx, cy, bs);
        } else {
            float side = screen.left - ox;
            padR = Math.min(side * 0.42f, h * 0.22f);
            padCx = ox + side / 2;
            padCy = oy + h - padR - 20 * dp;
            bs = Math.min(56 * dp, side / 2.8f);
            float cx = screen.right + side / 2, cy = oy + h - bs * 1.6f - 12 * dp;
            place(cx, cy, bs);
        }
    }

    /** four buttons in a diamond around (cx, cy), Esc small above */
    private void place(float cx, float cy, float s) {
        float d = s * 0.75f;
        set(padButtons[0], cx, cy + d, s);      // Пробел: bottom
        set(padButtons[1], cx + d, cy, s);      // Ctrl: right
        set(padButtons[2], cx - d, cy, s);      // Alt: left
        set(padButtons[3], cx, cy - d, s);      // Enter: top
        float e = s * 0.6f;
        padButtons[4].set(cx + d + s / 2 - e, cy - d - s / 2, cx + d + s / 2, cy - d - s / 2 + e * 0.7f); // Esc
    }

    private static void set(RectF r, float cx, float cy, float s) {
        r.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(0xFF0E1014);
        drawPicture(c);
        if (paused) drawPauseSign(c);
        if (!landscape) {
            fill.setColor(0xFF181B22);
            c.drawRect(toolbar, fill);
            String t = System.currentTimeMillis() < noteUntil && note != null ? note : title;
            text.setTextSize(15 * dp);
            text.setColor(System.currentTimeMillis() < noteUntil ? 0xFFFFE070 : 0xFF9AA0AA);
            float left = buttons.get(0).r.right + 8 * dp, right = findButton(B_PAUSE).r.left - 8 * dp;
            String s = t;
            while (s.length() > 3 && text.measureText(s) > right - left) s = s.substring(0, s.length() - 2);
            if (!s.equals(t)) s += "…";
            c.drawText(s, (left + right) / 2, toolbar.centerY() + 5 * dp, text);
            if (System.currentTimeMillis() < noteUntil) postInvalidateDelayed(noteUntil - System.currentTimeMillis() + 20);
        } else if (System.currentTimeMillis() < noteUntil && note != null) {
            // landscape: the note under the left buttons, outside the picture
            Btn b = findButton(B_BACK5);
            text.setTextSize(13 * dp);
            text.setColor(0xFFFFE070);
            c.drawText(note, b.r.centerX(), b.r.bottom + 22 * dp, text);
            postInvalidateDelayed(noteUntil - System.currentTimeMillis() + 20);
        }
        for (Btn b : buttons) drawButton(c, b);
        if (padVisible()) drawPad(c);
        if (keyboardVisible()) drawKeyboard(c);
    }

    private Btn findButton(int id) {
        for (Btn b : buttons) if (b.id == id) return b;
        return buttons.get(0);
    }

    private void drawPicture(Canvas c) {
        if (emu == null) return;
        int w, h;
        synchronized (emu.picture) {
            w = emu.picW;
            h = emu.picH;
            if (w <= 0 || h <= 0) return;
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            bitmap.setPixels(emu.picture, 0, w, 0, 0, w, h);
        }
        // keep the picture's own aspect (4:3) inside the screen area
        double asp = aspect();
        float sw = screen.width(), sh = screen.height();
        if (sw / sh > asp) sw = (float) (sh * asp);
        else sh = (float) (sw / asp);
        RectF dst = new RectF(screen.centerX() - sw / 2, screen.centerY() - sh / 2, screen.centerX() + sw / 2, screen.centerY() + sh / 2);
        bmpPaint.setFilterBitmap(w < dst.width() / 1.5f ? false : true);
        c.drawBitmap(bitmap, new Rect(0, 0, w, h), dst, bmpPaint);
    }

    private void drawPauseSign(Canvas c) {
        float s = Math.min(screen.width(), screen.height()) * 0.16f;
        float cx = screen.centerX(), cy = screen.centerY();
        fill.setColor(0x66000000);
        c.drawCircle(cx, cy, s, fill);
        fill.setColor(0xAAFFFFFF);
        float bw = s * 0.22f, bh = s * 0.9f;
        c.drawRect(cx - bw * 1.6f, cy - bh / 2, cx - bw * 0.6f, cy + bh / 2, fill);
        c.drawRect(cx + bw * 0.6f, cy - bh / 2, cx + bw * 1.6f, cy + bh / 2, fill);
    }

    private void drawButton(Canvas c, Btn b) {
        boolean accent = (b.id == B_SPEED && !"1×".equals(speedLabel)) || (b.id == B_PAUSE && paused);
        fill.setColor(accent ? 0xFF3A5BD9 : 0xFF262A33);
        c.drawRoundRect(b.r, 8 * dp, 8 * dp, fill);
        String label = b.id == B_SPEED ? speedLabel : b.id == B_PAUSE ? (paused ? "▶" : "⏸") : b.label;
        text.setColor(Color.WHITE);
        text.setTextSize(Math.min(b.r.height() * 0.42f, 17 * dp));
        c.drawText(label, b.r.centerX(), b.r.centerY() + text.getTextSize() * 0.35f, text);
    }

    private void drawPad(Canvas c) {
        int alpha = landscape ? 0x99 : 0xFF;
        fill.setColor((alpha << 24) | 0x262A33);
        c.drawCircle(padCx, padCy, padR, fill);
        fill.setColor((alpha << 24) | 0x3A3F4B);
        float arm = padR * 0.36f;
        c.drawRoundRect(padCx - arm, padCy - padR * 0.9f, padCx + arm, padCy + padR * 0.9f, 6 * dp, 6 * dp, fill);
        c.drawRoundRect(padCx - padR * 0.9f, padCy - arm, padCx + padR * 0.9f, padCy + arm, 6 * dp, 6 * dp, fill);
        text.setColor(0xCCFFFFFF);
        text.setTextSize(padR * 0.22f);
        float o = padR * 0.65f, d = text.getTextSize() * 0.35f;
        c.drawText("↑", padCx, padCy - o + d, text);
        c.drawText("↓", padCx, padCy + o + d, text);
        c.drawText("←", padCx - o, padCy + d, text);
        c.drawText("→", padCx + o, padCy + d, text);
        for (int i = 0; i < padButtons.length; i++) {
            RectF r = padButtons[i];
            boolean down = held[PAD_KEYS[i]] > 0;
            fill.setColor(down ? 0xFF5A7DFF : ((alpha << 24) | (i == 4 ? 0x3A3A48 : 0x30343F)));
            if (i == 4) c.drawRoundRect(r, 6 * dp, 6 * dp, fill);
            else c.drawCircle(r.centerX(), r.centerY(), r.width() / 2, fill);
            text.setColor(Color.WHITE);
            text.setTextSize(Math.min(r.height() * (i == 4 ? 0.45f : 0.26f), 15 * dp));
            c.drawText(PAD_LABELS[i], r.centerX(), r.centerY() + text.getTextSize() * 0.35f, text);
        }
    }

    private void drawKeyboard(Canvas c) {
        if (landscape) {
            fill.setColor(0xD0101218);
            c.drawRect(controls, fill);
        }
        for (KeyCap k : keys) {
            boolean down = held[k.key.code] > 0;
            boolean special = k.key.label.length() > 1 && !k.key.label.startsWith("F");
            fill.setColor(down ? 0xFF5A7DFF : special ? 0xFF3A3A48 : 0xFF2A2A31);
            c.drawRoundRect(k.r, 5 * dp, 5 * dp, fill);
            text.setColor(Color.WHITE);
            float ts = k.key.label.length() == 1 ? k.r.height() * 0.48f : k.r.height() * 0.32f;
            text.setTextSize(Math.min(ts, (k.key.label.length() == 1 ? 18 : 12) * dp));
            c.drawText(k.key.label, k.r.centerX(), k.r.centerY() + text.getTextSize() * 0.35f, text);
        }
    }

    // ------------------------------------------------------------------ touch

    private void press(int code, boolean down) {
        if (code <= 0 || code >= held.length) return;
        if (down) {
            if (held[code]++ == 0) host.onKey(code, true);
        } else if (held[code] > 0) {
            if (--held[code] == 0) host.onKey(code, false);
        }
    }

    /** all keys up (pause, leaving the screen) */
    void releaseAll() {
        for (int i = 0; i < held.length; i++) {
            if (held[i] > 0) {
                held[i] = 0;
                host.onKey(i, false);
            }
        }
        padDir[0] = padDir[1] = 0;
        fingers.clear();
        screenFinger = -1;
        invalidate();
    }

    private int hitTest(float x, float y) {
        for (Btn b : buttons) if (b.r.contains(x, y)) return T_BUTTON;
        if (keyboardVisible() && controls.contains(x, y)) {
            for (KeyCap k : keys) if (k.r.contains(x, y)) return T_KEY + k.key.code;
            return T_NONE;
        }
        if (padVisible()) {
            for (int i = 0; i < padButtons.length; i++) {
                RectF r = new RectF(padButtons[i]);
                r.inset(-6 * dp, -6 * dp);
                if (r.contains(x, y)) return T_KEY + PAD_KEYS[i];
            }
            float dx = x - padCx, dy = y - padCy;
            if (dx * dx + dy * dy < padR * padR * 1.3f) return T_PAD;
        }
        if (screen.contains(x, y)) return T_SCREEN;
        return T_NONE;
    }

    private Btn buttonAt(float x, float y) {
        for (Btn b : buttons) if (b.r.contains(x, y)) return b;
        return null;
    }

    private void updatePad(float x, float y, boolean release) {
        int h = 0, v = 0;
        if (!release) {
            float dx = x - padCx, dy = y - padCy;
            float dead = padR * 0.22f;
            if (dx * dx + dy * dy > dead * dead) {
                double a = Math.toDegrees(Math.atan2(dy, dx)); // 8 directions
                if (a > -67.5 && a < 67.5) h = PcKeys.RIGHT;
                if (a > 112.5 || a < -112.5) h = PcKeys.LEFT;
                if (a > 22.5 && a < 157.5) v = PcKeys.DOWN;
                if (a < -22.5 && a > -157.5) v = PcKeys.UP;
            }
        }
        if (h != padDir[0]) {
            press(padDir[0], false);
            press(h, true);
            padDir[0] = h;
        }
        if (v != padDir[1]) {
            press(padDir[1], false);
            press(v, true);
            padDir[1] = v;
        }
        invalidate();
    }

    private int screenFingers() {
        int n = 0;
        for (int i = 0; i < fingers.size(); i++) if (fingers.valueAt(i) == T_SCREEN) n++;
        return n;
    }

    private void sendPointer(float x, float y, boolean pressed) {
        int px = (int) Math.max(-32767, Math.min(32767, (x - screen.left) / screen.width() * 65534 - 32767));
        int py = (int) Math.max(-32767, Math.min(32767, (y - screen.top) / screen.height() * 65534 - 32767));
        host.onPointer(px, py, pressed, Math.max(1, screenFingers()));
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        int idx = ev.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int id = ev.getPointerId(idx);
                float x = ev.getX(idx), y = ev.getY(idx);
                int t = hitTest(x, y);
                fingers.put(id, t);
                if (t == T_BUTTON) {
                    Btn b = buttonAt(x, y);
                    if (b != null) click(b.id);
                } else if (t >= T_KEY) {
                    press(t - T_KEY, true);
                    invalidate();
                } else if (t == T_PAD) {
                    updatePad(x, y, false);
                } else if (t == T_SCREEN) {
                    if (screenFinger < 0) screenFinger = id;
                    int i = ev.findPointerIndex(screenFinger);
                    if (i >= 0) sendPointer(ev.getX(i), ev.getY(i), true);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < ev.getPointerCount(); i++) {
                    int id = ev.getPointerId(i);
                    Integer t = fingers.get(id);
                    if (t == null) continue;
                    if (t == T_PAD) updatePad(ev.getX(i), ev.getY(i), false);
                    else if (t == T_SCREEN && id == screenFinger) sendPointer(ev.getX(i), ev.getY(i), true);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean all = action != MotionEvent.ACTION_POINTER_UP;
                for (int i = 0; i < ev.getPointerCount(); i++) {
                    if (!all && i != idx) continue;
                    int id = ev.getPointerId(i);
                    Integer t = fingers.get(id);
                    if (t == null) continue;
                    if (t >= T_KEY) press(t - T_KEY, false);
                    else if (t == T_PAD) updatePad(0, 0, true);
                    else if (t == T_SCREEN) {
                        if (id == screenFinger) {
                            sendPointer(ev.getX(i), ev.getY(i), screenFingers() > 1);
                            fingers.remove(id);
                            screenFinger = -1;
                            for (int j = 0; j < fingers.size(); j++) {
                                if (fingers.valueAt(j) == T_SCREEN) {
                                    screenFinger = fingers.keyAt(j);
                                    break;
                                }
                            }
                            if (screenFinger < 0) host.onPointer(0, 0, false, 0);
                            continue;
                        }
                    }
                    fingers.remove(id);
                }
                if (all) {
                    fingers.clear();
                    screenFinger = -1;
                }
                invalidate();
                return true;
            }
            default:
                return true;
        }
    }

    private void click(int id) {
        switch (id) {
            case B_MENU: host.onMenu(); break;
            case B_PAUSE: host.onPauseToggle(); break;
            case B_BACK1: host.onRewind(1); break;
            case B_BACK5: host.onRewind(5); break;
            case B_SPEED: host.onSpeed(); break;
            case B_SAVE: host.onQuickSave(); break;
            case B_LOAD: host.onQuickLoad(); break;
            case B_CONTROLS: host.onControlsToggle(); break;
            default: break;
        }
        invalidate();
    }
}
