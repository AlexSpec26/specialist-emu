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
 * The game screen: the DOS picture, the buttons (menu, pause, rewind 5 s, speed, quick
 * save / load) and the controls — a PC keyboard or a game pad (arrows + Ctrl, Alt, Space,
 * Enter) with a floating stick: it appears where the finger comes down. The picture works as a touch pad for the mouse: drag moves, tap clicks, two-finger
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

    private static final int B_MENU = 1, B_PAUSE = 2, B_BACK5 = 4, B_SPEED = 5, B_SAVE = 6,
            B_LOAD = 7, B_CONTROLS = 8;
    static final int MAX_PAD_BUTTONS = 8;
    /** the game pad's buttons: the PC key of each (set per game in the menu) */
    int[] padKeys = PcKeys.DEFAULT_PAD.clone();
    /** what the cross sends: PcKeys.DPAD_ARROWS, _WASD or _NUMPAD */
    int dpadMode = PcKeys.DPAD_ARROWS;

    private final float dp;
    private final Host host;
    private EmuThread emu;
    private Bitmap bitmap;
    private int[] pixels = new int[0];

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private final RectF screen = new RectF();
    /** where the picture is actually drawn: its 4:3 (or whatever) inside screen */
    private final RectF picture = new RectF();
    /** draws the picture through the phone's video chip (screen modes); null or failed: drawn here */
    ScreenRenderer screenRenderer;
    Runnable requestRender;
    /** the pause magnifier: 1…8, and the source point (0..1) in the middle */
    private float zoom = 1, zoomCx = 0.5f, zoomCy = 0.5f;
    /** a tap on the picture pauses / resumes instead of being the mouse (set per game) */
    boolean tapPauses;
    private final RectF toolbar = new RectF();
    private final RectF controls = new RectF();
    private final List<Btn> buttons = new ArrayList<>();
    private final List<KeyCap> keys = new ArrayList<>();
    private final RectF[] padButtons = new RectF[MAX_PAD_BUTTONS];
    /** where each button answers a touch: its cell, a little larger than the circle */
    private final RectF[] padCells = new RectF[MAX_PAD_BUTTONS];
    /** the stick: anywhere in padZone a touch sets its centre (as in Dysmantle); padCx/padCy is
     * where it is drawn at rest, padR its radius */
    private final RectF padZone = new RectF();
    private float padCx, padCy, padR;
    private boolean stickOn;
    private int stickFinger = -1;
    private float stickCx, stickCy, stickX, stickY;
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
    private static final int T_NONE = 0, T_SCREEN = 1, T_PAD = 2, T_BUTTON = 3, T_LOOK = 4, T_KEY = 1000;
    private final SparseArray<Integer> fingers = new SparseArray<>();
    private final int[] held = new int[512]; // how many sources hold each key
    private final int[] padDir = new int[2]; // keys held by the pad's direction
    private int screenFinger = -1;

    GameView(Context c, Host host) {
        super(c);
        this.host = host;
        dp = c.getResources().getDisplayMetrics().density;
        text.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < padButtons.length; i++) {
            padButtons[i] = new RectF();
            padCells[i] = new RectF();
        }
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
            int[] ids = {B_BACK5, B_SPEED, B_SAVE, B_LOAD};
            String[] labels = {"⟲ 5 с", speedLabel, "💾", "📂"};
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
            int[] leftIds = {B_MENU, B_BACK5};
            String[] leftLabels = {"☰", "⟲ 5 с"};
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
        // game pad: the stick's zone on the left, buttons on the right
        float gap = 8 * dp;
        float quarter = (w + insL + insR) / 4f; // one button: a quarter of the screen wide
        if (!landscape) {
            padZone.set(controls.left, controls.top, controls.centerX(), controls.bottom);
            padR = Math.min(padZone.width(), padZone.height()) * 0.3f;
            padCx = padZone.centerX();
            padCy = padZone.centerY();
            place(new RectF(controls.centerX() + gap, controls.top + gap, controls.right - gap, controls.bottom - gap), quarter);
        } else {
            float side = screen.left - ox;
            float belowLeft = oy;
            float belowRight = oy;
            for (Btn b : buttons) {
                if (b.r.centerX() < screen.left) belowLeft = Math.max(belowLeft, b.r.bottom);
                else belowRight = Math.max(belowRight, b.r.bottom);
            }
            padZone.set(ox, belowLeft + gap, screen.left, oy + h);
            padR = Math.min(side * 0.4f, padZone.height() * 0.32f);
            padCx = padZone.centerX();
            padCy = padZone.bottom - padR - 20 * dp;
            place(new RectF(screen.right + gap, belowRight + 2 * gap, ox + w - gap, oy + h - gap), quarter);
        }
    }

    /**
     * The buttons as large as the area allows (the more of them, the smaller), but no wider than
     * maxD; the grid with the most room per button wins, its rows filled left to right, top to
     * bottom, and the whole grid in the middle of the area.
     */
    private void place(RectF area, float maxD) {
        int n = padKeys.length;
        if (n == 0) return;
        float g = 12 * dp;
        int bestCols = 1;
        float best = 0;
        for (int cols = 1; cols <= n; cols++) {
            int rows = (n + cols - 1) / cols;
            float s = Math.min((area.width() - g * (cols - 1)) / cols, (area.height() - g * (rows - 1)) / rows);
            if (s > best + 0.5f) {
                best = s;
                bestCols = cols;
            }
        }
        int cols = bestCols, rows = (n + cols - 1) / cols;
        float d = Math.max(16 * dp, Math.min(best, maxD));
        // spare room goes into the gaps (up to 0.3 of a button), so the grid spreads over the area
        float gx = cols > 1 ? Math.min((area.width() - cols * d) / (cols - 1), d * 0.3f) : 0;
        float gy = rows > 1 ? Math.min((area.height() - rows * d) / (rows - 1), d * 0.3f) : 0;
        gx = Math.max(gx, g * 0.5f);
        gy = Math.max(gy, g * 0.5f);
        float gridH = rows * d + (rows - 1) * gy;
        float y0 = area.centerY() - gridH / 2;
        for (int i = 0; i < n; i++) {
            int r = i / cols, c = i % cols;
            int inRow = Math.min(cols, n - r * cols);
            float rowW = inRow * d + (inRow - 1) * gx;
            float x0 = area.centerX() - rowW / 2;
            float cx = x0 + c * (d + gx) + d / 2, cy = y0 + r * (d + gy) + d / 2;
            set(padButtons[i], cx, cy, d);
            padCells[i].set(cx - (d + gx) / 2, cy - (d + gy) / 2, cx + (d + gx) / 2, cy + (d + gy) / 2);
        }
    }

    private static void set(RectF r, float cx, float cy, float s) {
        r.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        updatePictureRect();
        boolean gl = screenRenderer != null && !screenRenderer.failed;
        if (gl) {
            // the picture's place stays transparent: the screen renderer's surface is under it
            c.save();
            if (android.os.Build.VERSION.SDK_INT >= 26) c.clipOutRect(picture);
            else c.clipRect(picture, android.graphics.Region.Op.DIFFERENCE);
            c.drawColor(BACKGROUND);
            c.restore();
        } else {
            c.drawColor(BACKGROUND);
            drawPicture(c);
        }
        if (zoom > 1.01f) drawZoomLabel(c);
        if (paused && zoom <= 1.01f) drawPauseSign(c);
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

    static final int BACKGROUND = 0xFF0E1014;

    /** the picture's rectangle (aspect kept inside screen); tells the renderer when it moves */
    private void updatePictureRect() {
        double asp = aspect();
        float sw = screen.width(), sh = screen.height();
        if (sw <= 0 || sh <= 0) return;
        if (sw / sh > asp) sw = (float) (sh * asp);
        else sh = (float) (sw / asp);
        float l = screen.centerX() - sw / 2, t = screen.centerY() - sh / 2;
        if (l != picture.left || t != picture.top || sw != picture.width() || sh != picture.height()) {
            picture.set(l, t, l + sw, t + sh);
            if (screenRenderer != null) {
                screenRenderer.setRect(picture.left, picture.top, picture.width(), picture.height());
                if (requestRender != null) requestRender.run();
            }
        }
    }

    private void drawZoomLabel(Canvas c) {
        text.setTextSize(13 * dp);
        text.setColor(0xFFFFE070);
        String s = String.format(java.util.Locale.ROOT, "×%.1f", zoom).replace('.', ',');
        c.drawText(s, picture.right - 28 * dp, picture.top + 20 * dp, text);
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
        // the stick: at rest a faint ring with the keys it sends; under the finger a ring where
        // the finger came down and a knob that leans its way
        float cx = stickOn ? stickCx : padCx, cy = stickOn ? stickCy : padCy;
        fill.setColor(((stickOn ? alpha : alpha / 2) << 24) | 0x262A33);
        c.drawCircle(cx, cy, padR, fill);
        text.setColor(stickOn ? 0xCCFFFFFF : 0x88FFFFFF);
        text.setTextSize(padR * 0.22f);
        float o = padR * 0.72f, d = text.getTextSize() * 0.35f;
        int[] dir = PcKeys.DPAD[dpadMode];
        c.drawText(PcKeys.label(dir[0]), cx, cy - o + d, text);
        c.drawText(PcKeys.label(dir[1]), cx, cy + o + d, text);
        c.drawText(PcKeys.label(dir[2]), cx - o, cy + d, text);
        c.drawText(PcKeys.label(dir[3]), cx + o, cy + d, text);
        float kx = cx, ky = cy;
        if (stickOn) {
            float dx = stickX - stickCx, dy = stickY - stickCy, len = (float) Math.hypot(dx, dy), lim = padR * 0.6f;
            if (len > lim) {
                dx *= lim / len;
                dy *= lim / len;
            }
            kx += dx;
            ky += dy;
        }
        boolean leaning = padDir[0] != 0 || padDir[1] != 0;
        fill.setColor(leaning ? 0xFF5A7DFF : ((stickOn ? alpha : alpha / 2) << 24) | 0x3A3F4B);
        c.drawCircle(kx, ky, padR * 0.4f, fill);
        for (int i = 0; i < padKeys.length; i++) {
            RectF r = padButtons[i];
            boolean down = held[padKeys[i]] > 0;
            fill.setColor(down ? 0xFF5A7DFF : ((alpha << 24) | 0x30343F));
            c.drawCircle(r.centerX(), r.centerY(), r.width() / 2, fill);
            String label = PcKeys.label(padKeys[i]);
            text.setColor(Color.WHITE);
            float ts = Math.min(r.height() * (label.length() <= 2 ? 0.4f : 0.26f), 28 * dp);
            text.setTextSize(ts);
            while (text.measureText(label) > r.width() * 0.86f && ts > 6 * dp) text.setTextSize(ts -= dp);
            c.drawText(label, r.centerX(), r.centerY() + text.getTextSize() * 0.35f, text);
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
        looking.clear();
        stickOn = false;
        stickFinger = -1;
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
            for (int i = 0; i < padKeys.length; i++) if (padCells[i].contains(x, y)) return T_KEY + padKeys[i];
            if (padZone.contains(x, y)) return stickFinger < 0 ? T_PAD : T_NONE;
        }
        // in the pause (or when a tap pauses) the picture is for looking: magnifier, not mouse
        if (screen.contains(x, y)) return paused || tapPauses ? T_LOOK : T_SCREEN;
        return T_NONE;
    }

    private Btn buttonAt(float x, float y) {
        for (Btn b : buttons) if (b.r.contains(x, y)) return b;
        return null;
    }

    /** the stick follows a finger: the centre is where it came down (and is dragged along when
     * the finger goes farther than the ring); the lean beyond a small dead zone gives 8 ways */
    private void updatePad(float x, float y, boolean release) {
        int h = 0, v = 0;
        if (!release) {
            stickX = x;
            stickY = y;
            float dx = x - stickCx, dy = y - stickCy, len = (float) Math.hypot(dx, dy);
            if (len > padR) {
                stickCx = x - dx * padR / len;
                stickCy = y - dy * padR / len;
                dx = x - stickCx;
                dy = y - stickCy;
                len = padR;
            }
            if (len > padR * 0.25f) {
                double a = Math.toDegrees(Math.atan2(dy, dx)); // 8 directions
                int[] dir = PcKeys.DPAD[dpadMode];
                if (a > -67.5 && a < 67.5) h = dir[3];
                if (a > 112.5 || a < -112.5) h = dir[2];
                if (a > 22.5 && a < 157.5) v = dir[1];
                if (a < -22.5 && a > -157.5) v = dir[0];
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

    // ------------------------------------------------------------------ the magnifier (pause)

    private final SparseArray<float[]> looking = new SparseArray<>();
    private long lookDownAt, lastTapAt;
    private float lookDownX, lookDownY;
    private boolean lookMoved;

    private void lookDown(int id, float x, float y) {
        looking.put(id, new float[]{x, y});
        if (looking.size() == 1) {
            lookDownAt = System.currentTimeMillis();
            lookDownX = x;
            lookDownY = y;
            lookMoved = false;
        } else {
            lookMoved = true; // two fingers: a pinch, not a tap
        }
    }

    private void lookMove(int id, float x, float y) {
        float[] p = looking.get(id);
        if (p == null) return;
        if (Math.hypot(x - lookDownX, y - lookDownY) > 12 * dp) lookMoved = true;
        if (!paused || picture.width() <= 0) {
            p[0] = x;
            p[1] = y;
            return;
        }
        if (looking.size() == 1) {
            // one finger drags the enlarged picture
            zoomCx -= (x - p[0]) / (picture.width() * zoom);
            zoomCy -= (y - p[1]) / (picture.height() * zoom);
        } else if (looking.keyAt(0) == id || looking.keyAt(1) == id) {
            // two fingers pinch; the point between them stays where it is
            float[] a = looking.valueAt(0), b = looking.valueAt(1);
            float ax = a[0], ay = a[1], bx = b[0], by = b[1];
            float before = (float) Math.hypot(ax - bx, ay - by);
            if (looking.keyAt(0) == id) { ax = x; ay = y; } else { bx = x; by = y; }
            float after = (float) Math.hypot(ax - bx, ay - by);
            if (before > 10 * dp && after > 10 * dp) {
                float fx = (ax + bx) / 2, fy = (ay + by) / 2;
                float u = zoomCx + (fx - picture.centerX()) / (picture.width() * zoom);
                float v = zoomCy + (fy - picture.centerY()) / (picture.height() * zoom);
                zoom = Math.max(1f, Math.min(8f, zoom * after / before));
                zoomCx = u - (fx - picture.centerX()) / (picture.width() * zoom);
                zoomCy = v - (fy - picture.centerY()) / (picture.height() * zoom);
            }
        }
        p[0] = x;
        p[1] = y;
        applyZoom();
    }

    private void lookUp(int id, float x, float y) {
        boolean last = looking.size() == 1 && looking.get(id) != null;
        looking.remove(id);
        if (!last || lookMoved || System.currentTimeMillis() - lookDownAt > 350) return;
        // a tap
        long now = System.currentTimeMillis();
        if (tapPauses) {
            host.onPauseToggle();
        } else if (paused && now - lastTapAt < 350) {
            resetZoom(); // double tap: the whole picture again
        }
        lastTapAt = now;
    }

    private void applyZoom() {
        float half = 0.5f / zoom;
        zoomCx = Math.max(half, Math.min(1 - half, zoomCx));
        zoomCy = Math.max(half, Math.min(1 - half, zoomCy));
        if (screenRenderer != null) {
            screenRenderer.zoom = zoom;
            screenRenderer.centerX = zoomCx;
            screenRenderer.centerY = zoomCy;
            if (requestRender != null) requestRender.run();
        }
        invalidate();
    }

    /** back to the whole picture (when the game goes on) */
    void resetZoom() {
        zoom = 1;
        zoomCx = zoomCy = 0.5f;
        applyZoom();
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
                    stickFinger = id;
                    stickOn = true;
                    stickCx = x;
                    stickCy = y;
                    updatePad(x, y, false);
                } else if (t == T_LOOK) {
                    lookDown(id, x, y);
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
                    else if (t == T_LOOK) lookMove(id, ev.getX(i), ev.getY(i));
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
                    else if (t == T_LOOK) lookUp(id, ev.getX(i), ev.getY(i));
                    else if (t == T_PAD) {
                        stickOn = false;
                        stickFinger = -1;
                        updatePad(0, 0, true);
                    }
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
                    if (stickOn) {
                        stickOn = false;
                        updatePad(0, 0, true);
                    }
                    stickFinger = -1;
                    fingers.clear();
                    looking.clear();
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
