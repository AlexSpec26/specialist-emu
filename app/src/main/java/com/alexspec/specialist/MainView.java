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

        void onScreenTapped();

        void onKeyboardToggled(boolean visible);

        void onKeyPicked(int code);

        void onPickCancelled();

        /** pause controls: 0 = 1 s back, 1 = one instruction back, 2 = one forward, 3 = 1 s forward */
        void onStep(int kind);
    }

    private static final int T_NONE = -1;
    private static final int T_JOY = -2;
    private static final int T_MENU = -3;
    private static final int T_KBD = -4;
    private static final int T_CONSUMED = -5;
    private static final int T_SCREEN = -6;
    private static final int T_MAP = -7;
    private static final int T_PANEL = -8;
    private static final int T_STEP = 3000;
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
    /** Paused (the emulation itself is paused by the activity); only a small sign on the picture. */
    private boolean paused;
    /** speed shown in the corner (×2, 1/8, 5 т/с …); null at real speed */
    String speedBadge;
    /** live memory map under the screen (portrait only) */
    boolean mapOn;
    /** live tape input (microphone), for the level indicator; null when off */
    com.alexspec.specialist.core.TapeDecoder tape;
    /** opacity of joystick and buttons, 0.1 .. 1 */
    float ctrlAlpha = 1f;

    private boolean keyboardVisible;
    private String pickPrompt; // non-null while choosing a key for a control

    // geometry
    private final RectF screenRect = new RectF();
    private boolean mapVisible;
    private float lastUpX, lastUpY;
    final MapPanel map;
    /** called when the tutorial is closed (so the owner can re-layout) */
    Runnable onTutorialClosed;
    private final RectF menuRect = new RectF();
    private final RectF[] stepRects = {new RectF(), new RectF(), new RectF(), new RectF()};
    private static final String[] STEP_LABELS = {"⏪ 1 с", "◀ 1", "1 ▶", "1 с ⏩"};
    /** after a step in the pause (portrait): the instruction and registers in their own band
     *  between the picture and the map / keyboard, until play goes on */
    private boolean stepInfo;
    private final RectF infoRect = new RectF();
    private static final int INFO_LINES = 5;

    void setPaused(boolean p) {
        if (paused == p) return;
        paused = p;
        if (!p && stepInfo) {
            stepInfo = false;
            layoutControls(getWidth(), getHeight());
        }
        invalidate();
    }

    /** a step was made: show the instruction and the registers (portrait only) */
    void showMachineInfo() {
        if (!stepInfo && paused && !landscape) {
            stepInfo = true;
            layoutControls(getWidth(), getHeight());
        }
        invalidate();
    }

    /** the pause controls exist only in portrait: landscape is just for playing */
    private boolean stepControls() {
        return paused && pickPrompt == null && machine.isRomLoaded() && !landscape;
    }
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
    private float insL, insT, insR, insB;

    /** a key at a place of the keyboard grid (columns and rows in key units) */
    private static final class VKey {
        final int code;
        final float gx, gy, gw, gh;
        final RectF r = new RectF();

        VKey(int code, float gx, float gy, float gw, float gh) {
            this.code = code;
            this.gx = gx;
            this.gy = gy;
            this.gw = gw;
            this.gh = gh;
        }
    }

    /** width of the side columns with the service keys, in key units */
    private static final float KBD_SIDE = 1.3f;
    private static final float KBD_COLS = 12 + 2 * KBD_SIDE;
    private static final int KBD_ROWS = 6;
    private final List<VKey> keys = new ArrayList<>();

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
        map = new MapPanel(emu, new MapPanel.Host() {
            @Override
            public void invalidate() {
                MainView.this.invalidate();
            }

            @Override
            public void postInvalidateDelayed(long ms) {
                MainView.this.postInvalidateDelayed(ms);
            }

            @Override
            public void onTutorialClosed() {
                layoutControls(getWidth(), getHeight());
                if (onTutorialClosed != null) onTutorialClosed.run();
            }
        }, dp);
    }

    void startTutorial() {
        releaseAll();
        setKeyboardVisible(false);
        map.startTutorial();
        layoutControls(getWidth(), getHeight());
        invalidate();
    }

    // ------------------------------------------------------------------ keyboard layout

    /**
     * The letters, digits and F-keys keep the original Specialist order (the scan matrix: rows
     * 5..1 top to bottom, columns 11..0 left to right). The service keys of matrix row 0 do not
     * crowd the bottom row any more: there only ПРОБЕЛ and the four arrows stay. ЗБ is where a
     * backspace usually is (end of the digit row), ВК is two rows high with ПС under it; АР2,
     * ТАБ, ПВ, Р/Л and НР (two rows) are on the left, ↖ is at the right of the arrows.
     */
    private void buildKeyboard() {
        keys.clear();
        float m = KBD_SIDE, rx = KBD_SIDE + 12;
        keys.add(new VKey(SpecKeys.ESC, 0, 0, m, 1));
        keys.add(new VKey(SpecKeys.TAB, 0, 1, m, 1));
        keys.add(new VKey(SpecKeys.RPT, 0, 2, m, 1));
        keys.add(new VKey(SpecKeys.LANG, 0, 3, m, 1));
        keys.add(new VKey(SpecKeys.SHIFT, 0, 4, m, 2));
        for (int r = SpecKeys.ROWS - 1; r >= 1; r--) {
            int y = SpecKeys.ROWS - 1 - r;
            // the Q row has 11 keys (its ЗБ moved up): shifted by half a key, as on a typewriter
            float x = r == 1 ? m + 0.5f : m;
            for (int c = SpecKeys.COLS - 1; c >= 0; c--) {
                int code = SpecKeys.code(c, r);
                if (code == SpecKeys.BACKSPACE) continue;
                keys.add(new VKey(code, x, y, 1, 1));
                x += 1;
            }
        }
        keys.add(new VKey(SpecKeys.SPACE, m, 5, 8, 1));
        keys.add(new VKey(SpecKeys.LEFT, m + 8, 5, 1, 1));
        keys.add(new VKey(SpecKeys.UP, m + 9, 5, 1, 1));
        keys.add(new VKey(SpecKeys.DOWN, m + 10, 5, 1, 1));
        keys.add(new VKey(SpecKeys.RIGHT, m + 11, 5, 1, 1));
        keys.add(new VKey(SpecKeys.BACKSPACE, rx, 1, m, 1));
        keys.add(new VKey(SpecKeys.ENTER, rx, 2, m, 2));
        keys.add(new VKey(SpecKeys.LF, rx, 4, m, 1));
        keys.add(new VKey(SpecKeys.HOME, rx, 5, m, 1));
    }

    // ------------------------------------------------------------------ public API

    boolean isKeyboardVisible() {
        return keyboardVisible;
    }

    void setKeyboardVisible(boolean v) {
        if (keyboardVisible == v) return;
        releaseAll();
        keyboardVisible = v;
        layoutControls(getWidth(), getHeight());
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
    @SuppressWarnings("deprecation")
    public android.view.WindowInsets onApplyWindowInsets(android.view.WindowInsets in) {
        float l = 0, t = 0, r = 0, b = 0;
        android.view.DisplayCutout cut = in.getDisplayCutout();
        if (cut != null) {
            l = cut.getSafeInsetLeft();
            t = cut.getSafeInsetTop();
            r = cut.getSafeInsetRight();
            b = cut.getSafeInsetBottom();
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            float rad = 0;
            int[] pos = {android.view.RoundedCorner.POSITION_TOP_LEFT, android.view.RoundedCorner.POSITION_TOP_RIGHT,
                    android.view.RoundedCorner.POSITION_BOTTOM_LEFT, android.view.RoundedCorner.POSITION_BOTTOM_RIGHT};
            for (int p : pos) {
                android.view.RoundedCorner rc = in.getRoundedCorner(p);
                if (rc != null) rad = Math.max(rad, rc.getRadius());
            }
            // a rectangle inset by r*(1-1/sqrt2) on both axes clears a corner of radius r
            float m = rad * 0.3f;
            l = Math.max(l, m);
            t = Math.max(t, m);
            r = Math.max(r, m);
            b = Math.max(b, m);
        } else {
            float m = 12 * dp;
            l = Math.max(l, m);
            t = Math.max(t, m);
            r = Math.max(r, m);
            b = Math.max(b, m);
        }
        // visible system bars: in portrait the navigation buttons stay on screen; landscape is
        // full screen (bars shown by a swipe only for a moment, over the picture)
        float nl = 0, nt = 0, nr = 0, nb = 0;
        boolean land = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        if (land) {
            // nothing
        } else if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets sb = in.getInsets(android.view.WindowInsets.Type.systemBars());
            nl = sb.left;
            nt = sb.top;
            nr = sb.right;
            nb = sb.bottom;
        } else {
            nl = in.getSystemWindowInsetLeft();
            nt = in.getSystemWindowInsetTop();
            nr = in.getSystemWindowInsetRight();
            nb = in.getSystemWindowInsetBottom();
        }
        l = Math.max(l, nl);
        t = Math.max(t, nt);
        r = Math.max(r, nr);
        b = Math.max(b, nb);
        if (l != insL || t != insT || r != insR || b != insB) {
            insL = l;
            insT = t;
            insR = r;
            insB = b;
            releaseAll();
            layoutControls(getWidth(), getHeight());
            invalidate();
        }
        return in;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        releaseAll();
        layoutControls(w, h);
    }

    private void layoutControls(int fullW, int fullH) {
        if (fullW == 0 || fullH == 0) return;
        final float ox = insL, oy = insT;
        final float w = fullW - insL - insR, h = fullH - insT - insB;
        landscape = fullW > fullH;
        float tb = 52 * dp;
        toolbarH = tb;
        float small = 46 * dp;
        if (!landscape) {
            float sh = w * (float) Specialist.HEIGHT / Specialist.WIDTH;
            screenRect.set(ox, oy, ox + w, oy + sh);
            toolbarY = oy + sh;
            menuRect.set(ox + 8 * dp, toolbarY + (tb - small) / 2, ox + 8 * dp + small * 1.4f, toolbarY + (tb + small) / 2);
            kbdBtnRect.set(ox + w - 8 * dp - small * 1.4f, menuRect.top, ox + w - 8 * dp, menuRect.bottom);
            float top = toolbarY + tb;
            if (stepInfo) {
                float lh = infoTextSize(w) * 1.25f;
                infoRect.set(ox, top, ox + w, top + lh * INFO_LINES + 8 * dp);
                top = infoRect.bottom;
            } else {
                infoRect.setEmpty();
            }
            float bottom = oy + h;
            float regionH = bottom - top;
            joyZone.set(0, top, fullW / 2f, fullH);
            btnZone.set(fullW / 2f, top, fullW, fullH);
            mapVisible = mapOn;
            if (mapVisible) {
                // memory map right under the toolbar; controls float over it (semi-transparent)
                boolean bits = emu.machine.trace.layout == com.alexspec.specialist.core.MemTrace.LAYOUT_BITS;
                // bits: the 8:1 strip fits the width, the extra height is room for zooming in
                float mh = bits ? w / 2f : Math.min(w, regionH - 60 * dp);
                float panelTop = Math.min(top + mh + 6 * dp, bottom - 280 * dp);
                if (map.isTutorialActive()) mh = Math.max(80 * dp, Math.min(mh, panelTop - 6 * dp - top));
                map.rect.set(ox, top, ox + w, top + mh);
                map.panelRect.set(ox + 6 * dp, Math.max(map.rect.bottom + 6 * dp, panelTop), ox + w - 6 * dp, bottom - 6 * dp);
                float r = w * 0.17f;
                float free = bottom - map.rect.bottom;
                float cy = free >= r * 2.4f ? map.rect.bottom + free / 2f : bottom - r * 1.2f;
                joyCx = ox + w * 0.25f;
                joyCy = cy;
                joyR = r;
                layoutButtons(ox + w * 0.75f, cy, r);
            } else {
                float cy = top + regionH * 0.5f;
                joyCx = ox + w * 0.27f;
                joyCy = cy;
                joyR = Math.min(w * 0.21f, regionH * 0.32f);
                layoutButtons(ox + w * 0.73f, cy, Math.min(w * 0.2f, regionH * 0.3f));
            }
            // low keys (roughly square), so the memory map stays visible above the keyboard
            float rowH = Math.min(regionH / KBD_ROWS, (mapVisible ? 34 : 46) * dp);
            kbdRect.set(ox, bottom - rowH * KBD_ROWS - 6 * dp, ox + w, bottom);
            if (mapVisible && keyboardVisible && map.rect.bottom > kbdRect.top - 4 * dp)
                map.rect.bottom = Math.max(map.rect.top + 40 * dp, kbdRect.top - 4 * dp);
        } else {
            float sw = Math.min(h * 1.5f, w * 0.64f);
            float sh = sw / 1.5f;
            float sx = ox + (w - sw) / 2f;
            screenRect.set(sx, oy + (h - sh) / 2f, sx + sw, oy + (h + sh) / 2f);
            toolbarY = -1;
            mapVisible = false;
            stepInfo = false;
            infoRect.setEmpty();
            menuRect.set(ox + 6 * dp, oy + 6 * dp, ox + 6 * dp + small * 1.4f, oy + 6 * dp + small);
            kbdBtnRect.set(ox + w - 6 * dp - small * 1.4f, oy + 6 * dp, ox + w - 6 * dp, oy + 6 * dp + small);
            joyZone.set(0, menuRect.bottom + 8 * dp, sx, fullH);
            btnZone.set(sx + sw, menuRect.bottom + 8 * dp, fullW, fullH);
            float side = sx - ox;
            joyCx = ox + side / 2f;
            joyCy = oy + h * 0.62f;
            joyR = Math.min(side * 0.42f, h * 0.26f);
            layoutButtons(ox + w - side / 2f, oy + h * 0.62f, Math.min(side * 0.42f, h * 0.26f));
            float kh = Math.min(h * 0.62f, KBD_ROWS * 54 * dp);
            kbdRect.set(ox, oy + h - kh, ox + w, oy + h);
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
        float rowH = (kbdRect.bottom - top) / KBD_ROWS;
        float unit = (kbdRect.width() - 2 * pad) / KBD_COLS;
        float left = kbdRect.left + pad;
        for (VKey k : keys) {
            float x = left + k.gx * unit, y = top + k.gy * rowH;
            k.r.set(x + pad / 2, y + pad / 2, x + k.gw * unit - pad / 2, y + k.gh * rowH - pad / 2);
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
        // pause: only a small translucent sign in the middle; the step buttons are outside the picture
        if (paused && machine.isRomLoaded() && pickPrompt == null) drawPauseSign(c);

        if (!machine.isRomLoaded()) {
            text.setTextSize(18 * dp);
            text.setColor(0xFFAAAAAA);
            c.drawText("Нет ПЗУ — откройте меню ☰", screenRect.centerX(), screenRect.centerY(), text);
        }

        if (toolbarY >= 0) {
            fill.setColor(0xFF15151A);
            c.drawRect(0, toolbarY, getWidth(), toolbarY + toolbarH, fill);
            if (title != null && !title.isEmpty() && !stepControls()) {
                text.setTextSize(15 * dp);
                text.setColor(0xFF9A9AA5);
                String t = title;
                float maxW = midRight() - midLeft() - 8 * dp;
                while (t.length() > 3 && text.measureText(t) > maxW) t = t.substring(0, t.length() - 2);
                if (!t.equals(title)) t = t + "…";
                c.drawText(t, (midLeft() + midRight()) / 2f, toolbarY + toolbarH / 2 + 5 * dp, text);
            }
        }
        drawStatus(c);
        drawUiButton(c, menuRect, "☰", false);
        drawUiButton(c, kbdBtnRect, "⌨", keyboardVisible);

        if (mapVisible) {
            map.programTitle = title;
            map.draw(c);
        }
        if (keyboardVisible) drawKeyboard(c);
        else if (!(mapVisible && map.isTutorialActive())) drawControls(c);
        if (stepControls()) drawStepButtons(c);
        if (!infoRect.isEmpty()) drawMachineInfo(c);
    }

    // speed badge and cassette indicator: outside the picture — in the toolbar (portrait) or
    // in the left margin under ☰ (landscape)
    private static final float TAPE_W = 74, STATUS_H = 20;

    private float badgeWidth() {
        if (speedBadge == null) return 0;
        text.setTextSize(13 * dp);
        return text.measureText(speedBadge) + 12 * dp;
    }

    /** the free middle of the toolbar, between ☰ / cassette and the speed / ⌨ */
    private float midLeft() {
        return menuRect.right + 6 * dp + (tape != null ? TAPE_W * dp + 6 * dp : 0);
    }

    private float midRight() {
        float bw = badgeWidth();
        return kbdBtnRect.left - 6 * dp - (bw > 0 ? bw + 6 * dp : 0);
    }

    private void drawStatus(Canvas c) {
        float h = STATUS_H * dp, gap = 6 * dp;
        float tx, ty, bx, by;
        if (toolbarY >= 0) {
            ty = by = toolbarY + (toolbarH - h) / 2f;
            tx = menuRect.right + gap;
            bx = kbdBtnRect.left - gap - badgeWidth();
        } else {
            tx = bx = menuRect.left;
            ty = menuRect.bottom + gap;
            by = ty + (tape != null ? h + gap : 0);
        }
        if (tape != null) drawTapeIndicator(c, tx, ty);
        if (speedBadge != null) {
            float bw = badgeWidth();
            fill.setColor(0xCC3A5BD9);
            c.drawRoundRect(bx, by, bx + bw, by + h, 8 * dp, 8 * dp, fill);
            text.setColor(Color.WHITE);
            c.drawText(speedBadge, bx + bw / 2f, by + h / 2f + 5 * dp, text);
        }
    }

    /** a small translucent pause sign in the middle of the picture */
    private void drawPauseSign(Canvas c) {
        float r = Math.min(26 * dp, screenRect.height() * 0.09f);
        float cx = screenRect.centerX(), cy = screenRect.centerY();
        fill.setColor(0x55000000);
        c.drawCircle(cx, cy, r, fill);
        fill.setColor(0x88FFFFFF);
        float bw = r * 0.22f, bh = r * 0.9f, gap = r * 0.16f;
        c.drawRect(cx - gap - bw, cy - bh / 2, cx - gap, cy + bh / 2, fill);
        c.drawRect(cx + gap, cy - bh / 2, cx + gap + bw, cy + bh / 2, fill);
    }

    /** Step buttons (portrait only): in the toolbar instead of the title. */
    private void layoutStepButtons() {
        float gap = 6 * dp;
        float left = midLeft(), right = midRight() + gap;
        float w = (right - left - gap * 4) / 4f;
        for (int i = 0; i < 4; i++) {
            float x = left + i * (w + gap);
            stepRects[i].set(x, menuRect.top, x + w, menuRect.bottom);
        }
    }

    private void drawStepButtons(Canvas c) {
        layoutStepButtons();
        for (int i = 0; i < 4; i++) {
            RectF r = stepRects[i];
            fill.setColor(0xDD2A3A80);
            c.drawRoundRect(r, 9 * dp, 9 * dp, fill);
            text.setColor(Color.WHITE);
            float ts = Math.min(r.height() * 0.42f, 16 * dp);
            text.setTextSize(ts);
            float tw = text.measureText(STEP_LABELS[i]);
            if (tw > r.width() * 0.9f) text.setTextSize(ts * r.width() * 0.9f / tw);
            c.drawText(STEP_LABELS[i], r.centerX(), r.centerY() + text.getTextSize() * 0.36f, text);
        }
    }

    /** the last and the next instruction with the registers (8080) */
    private void drawMachineInfo(Canvas c) {
        com.alexspec.specialist.core.I8080 z = machine.cpu;
        int[] len = new int[1];
        String ins = com.alexspec.specialist.core.Dis8080.dis(machine::rawRead, z.pc, len);
        StringBuilder hex = new StringBuilder();
        for (int k = 0; k < len[0]; k++) hex.append(String.format("%02X", machine.rawRead((z.pc + k) & 0xFFFF)));
        String l1 = String.format(java.util.Locale.US, "→ %04X  %-6s %s", z.pc, hex, ins);
        int lp = machine.lastInstrPc;
        String l0 = lp < 0 ? "" : String.format(java.util.Locale.US, "✓ %04X  %s", lp,
                com.alexspec.specialist.core.Dis8080.dis(machine::rawRead, lp, null));
        int f = z.getF();
        StringBuilder flags = new StringBuilder();
        String names = "SZ-A-P-C";
        for (int k = 0; k < 8; k++) if (names.charAt(k) != '-') flags.append((f & (0x80 >> k)) != 0 ? names.charAt(k) : '·');
        String l2 = String.format(java.util.Locale.US, "A %02X  BC %02X%02X  DE %02X%02X  HL %02X%02X", z.a, z.b, z.c, z.d, z.e, z.h, z.l);
        String l3 = String.format(java.util.Locale.US, "SP %04X  %s  %s", z.sp, flags, z.inte ? "EI" : "DI");
        long t = machine.time();
        String l4 = String.format(java.util.Locale.US, "команда %d, такт %d (%.2f с)", machine.instr, t, t / (double) Specialist.CPU_HZ);
        String[] lines = {l0, l1, l2, l3, l4};
        android.graphics.Typeface old = text.getTypeface();
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setTextAlign(Paint.Align.LEFT);
        float ts = infoTextSize(infoRect.width());
        text.setTextSize(ts);
        float lh = ts * 1.25f;
        // its own band between the picture (toolbar) and the map / keyboard: nothing is covered
        fill.setColor(0xFF0C0C12);
        c.drawRect(infoRect, fill);
        float bx = infoRect.left + 10 * dp, by = infoRect.top + 4 * dp;
        for (int i = 0; i < lines.length; i++) {
            boolean next = lines[i].startsWith("→"), done = lines[i].startsWith("✓");
            text.setColor(next ? 0xFFFFFF60 : done ? 0xFF20E8FF : 0xFFD0D0DA);
            c.drawText(lines[i], bx, by + (i + 0.8f) * lh, text);
        }
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(old);
    }

    private float infoTextSize(float width) {
        return Math.min(13 * dp, width / 30f);
    }

    private void drawUiButton(Canvas c, RectF r, String label, boolean active) {
        fill.setColor(active ? 0xCC3A5BD9 : 0x99303038);
        c.drawRoundRect(r, 10 * dp, 10 * dp, fill);
        text.setColor(Color.WHITE);
        text.setTextSize(r.height() * 0.5f);
        c.drawText(label, r.centerX(), r.centerY() + r.height() * 0.18f, text);
    }

    /** cassette indicator: input level bar and a "signal" lamp */
    private void drawTapeIndicator(Canvas c, float x, float y) {
        float w = TAPE_W * dp, h = STATUS_H * dp;
        fill.setColor(0xCC101018);
        c.drawRoundRect(x, y, x + w, y + h, 6 * dp, 6 * dp, fill);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(12 * dp);
        text.setColor(Color.WHITE);
        c.drawText("📼", x + 4 * dp, y + 15 * dp, text);
        text.setTextAlign(Paint.Align.CENTER);
        // level: -50 dBFS .. 0 dBFS
        float p = tape.peak();
        float db = p > 1e-5f ? (float) (20 * Math.log10(p)) : -100f;
        float lvl = Math.max(0f, Math.min(1f, (db + 50f) / 50f));
        float bx = x + 24 * dp, bw = 32 * dp, by = y + 7 * dp, bh = 6 * dp;
        fill.setColor(0xFF33333D);
        c.drawRect(bx, by, bx + bw, by + bh, fill);
        fill.setColor(lvl > 0.92f ? 0xFFFF4040 : lvl > 0.45f ? 0xFF40E060 : 0xFFE0C040);
        c.drawRect(bx, by, bx + bw * lvl, by + bh, fill);
        boolean signal = tape.edgesPerSecond() > 500;
        fill.setColor(signal ? 0xFF40FF60 : 0xFF404048);
        c.drawCircle(x + w - 8 * dp, y + h / 2f, 4 * dp, fill);
    }

    /** applies the user's control opacity to a colour */
    private int ca(int color) {
        int a = (color >>> 24) & 0xFF;
        return ((int) (a * ctrlAlpha) << 24) | (color & 0xFFFFFF);
    }

    private void drawControls(Canvas c) {
        // joystick
        fill.setColor(ca(0x33FFFFFF));
        c.drawCircle(joyCx, joyCy, joyR, fill);
        stroke.setColor(ca(0x66FFFFFF));
        stroke.setStrokeWidth(2 * dp);
        c.drawCircle(joyCx, joyCy, joyR, stroke);
        text.setTextSize(joyR * 0.28f);
        drawArrow(c, "▲", joyCx, joyCy - joyR * 0.7f, (joyDirs & DIR_UP) != 0);
        drawArrow(c, "▼", joyCx, joyCy + joyR * 0.7f, (joyDirs & DIR_DOWN) != 0);
        drawArrow(c, "◀", joyCx - joyR * 0.7f, joyCy, (joyDirs & DIR_LEFT) != 0);
        drawArrow(c, "▶", joyCx + joyR * 0.7f, joyCy, (joyDirs & DIR_RIGHT) != 0);
        fill.setColor(ca(joyPointer >= 0 ? 0xDD5A7DFF : 0x99A0A0B0));
        c.drawCircle(knobX, knobY, joyR * 0.38f, fill);

        // buttons
        int n = Math.min(buttonCount, ControlConfig.MAX_BUTTONS);
        for (int i = 0; i < n; i++) {
            boolean down = btnDown[i] > 0;
            fill.setColor(ca(down ? 0xEE5A7DFF : 0x66FFFFFF));
            c.drawCircle(btnX[i], btnY[i], btnR, fill);
            stroke.setColor(ca(0x99FFFFFF));
            c.drawCircle(btnX[i], btnY[i], btnR, stroke);
            String label = shortLabel(config.keys[ControlConfig.BUTTON0 + i]);
            text.setColor(ca(0xFFFFFFFF));
            float ts = btnR * 0.62f;
            text.setTextSize(ts);
            float tw = text.measureText(label);
            if (tw > btnR * 1.6f) text.setTextSize(ts * btnR * 1.6f / tw);
            c.drawText(label, btnX[i], btnY[i] + text.getTextSize() * 0.36f, text);
        }
    }

    private void drawArrow(Canvas c, String a, float x, float y, boolean on) {
        text.setColor(ca(on ? 0xFFFFFFFF : 0x77FFFFFF));
        c.drawText(a, x, y + text.getTextSize() * 0.36f, text);
    }

    private static String keyCapLabel(int code) {
        String l = SpecKeys.label(code);
        return "Пробел".equals(l) ? "ПРОБЕЛ" : l;
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
        for (VKey k : keys) {
            {
                boolean down = k.code == SpecKeys.SHIFT ? (shiftLatched || shiftHeld) : keyDownCount[k.code] > 0;
                int bg;
                boolean service = k.code == SpecKeys.SHIFT || k.code == SpecKeys.BACKSPACE
                        || (k.code % SpecKeys.ROWS == 0 && k.code != SpecKeys.SPACE);
                if (down) bg = 0xFF5A7DFF;
                else if (service) bg = 0xFF3A3A48;
                else if (k.code % SpecKeys.ROWS == 5) bg = 0xFF33302A; // function keys
                else bg = 0xFF2A2A31;
                fill.setColor(bg);
                c.drawRoundRect(k.r, rad, rad, fill);
                String main = keyCapLabel(k.code);
                String sec = SpecKeys.label2(k.code);
                boolean letterish = main.length() == 1;
                text.setColor(Color.WHITE);
                float kh = Math.min(k.r.height(), k.r.width() * 1.4f);
                float ts = Math.min(kh * (letterish ? 0.5f : 0.34f), (letterish ? 19 : 13) * dp);
                text.setTextSize(ts);
                float tw = text.measureText(main);
                if (tw > k.r.width() * 0.84f) text.setTextSize(ts * k.r.width() * 0.84f / tw);
                float cx = sec.isEmpty() ? k.r.centerX() : k.r.left + k.r.width() * 0.42f;
                float cy = k.r.centerY() + text.getTextSize() * 0.36f;
                c.drawText(main, cx, cy, text);
                if (!sec.isEmpty()) {
                    text.setColor(0xFFFF6A5A);
                    text.setTextSize(Math.min(k.r.height() * 0.24f, 9 * dp));
                    text.setTextAlign(Paint.Align.RIGHT);
                    c.drawText(sec, k.r.right - 2 * dp, k.r.bottom - 2.5f * dp, text);
                    text.setTextAlign(Paint.Align.CENTER);
                }
            }
        }
    }

    // ------------------------------------------------------------------ touch

    private int hitTest(float x, float y) {
        if (inflate(menuRect, 8 * dp).contains(x, y)) return T_MENU;
        if (inflate(kbdBtnRect, 8 * dp).contains(x, y)) return T_KBD;
        if (stepControls()) {
            layoutStepButtons();
            for (int i = 0; i < 4; i++) if (stepRects[i].contains(x, y)) return T_STEP + i;
        }
        if (screenRect.contains(x, y) && !(keyboardVisible && kbdRect.contains(x, y))) return T_SCREEN;
        if (keyboardVisible) {
            if (kbdRect.contains(x, y)) {
                VKey best = null;
                float bestD = Float.MAX_VALUE;
                for (VKey k : keys) {
                    {
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
            // the map stays usable (zoom, drag, inspect) while the keyboard is open
            if (mapVisible && map.rect.contains(x, y)) return T_MAP;
            return T_NONE;
        }
        if (mapVisible && map.panelContains(x, y)) return T_PANEL;
        if (mapVisible && map.rect.contains(x, y)) {
            if (map.isTutorialActive()) return T_MAP;
            // over the map only the controls themselves react, everything else inspects memory
            int bb = hitButton(x, y, 1.0f);
            if (bb >= 0) return T_BUTTON + bb;
            float dx = x - joyCx, dy = y - joyCy;
            if (dx * dx + dy * dy <= joyR * joyR * 1.3f) return T_JOY;
            return T_MAP;
        }
        if (mapVisible && map.isTutorialActive()) return T_NONE;
        int b = hitButton(x, y, 1.35f);
        if (b >= 0) return T_BUTTON + b;
        if (joyZone.contains(x, y)) return T_JOY;
        return T_NONE;
    }

    /** the field where the map can be pinched: everything under the toolbar, not the picture */
    private boolean inMapField(float x, float y, int t) {
        if (!mapVisible || map.isTutorialActive() || pickPrompt != null) return false;
        if (t == T_MENU || t == T_KBD || t == T_PANEL || t == T_SCREEN || t >= T_STEP) return false;
        return y >= toolbarY + toolbarH && !screenRect.contains(x, y);
    }

    private boolean mapGestureOn() {
        for (int i = 0; i < pointers.size(); i++) if (pointers.valueAt(i) == T_MAP) return true;
        return false;
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
                if (t != T_MAP && inMapField(x, y, t) && (t == T_NONE || mapGestureOn())) t = T_MAP;
                pointers.put(id, t);
                press(id, t, x, y);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < ev.getPointerCount(); i++) {
                    int id = ev.getPointerId(i);
                    int t = pointers.get(id, T_NONE);
                    float x = ev.getX(i), y = ev.getY(i);
                    if (t == T_MAP) {
                        map.move(id, x, y);
                    } else if (t == T_PANEL) {
                        map.panelMove(id, x, y);
                    } else if (t == T_JOY && id == joyPointer) {
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
                lastUpX = ev.getX(idx);
                lastUpY = ev.getY(idx);
                pointers.delete(id);
                release(id, t);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                map.cancel();
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
        if (t >= T_STEP) {
            haptic();
            host.onStep(t - T_STEP);
            showMachineInfo();
            return;
        }
        if (t == T_MENU || t == T_KBD || t == T_SCREEN) {
            haptic();
            return;
        }
        if (t == T_MAP) {
            map.down(id, x, y);
            return;
        }
        if (t == T_PANEL) {
            map.panelDown(id, x, y);
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
        if (t >= T_STEP) return;
        if (t == T_MAP) {
            map.up(id, lastUpX, lastUpY);
            return;
        }
        if (t == T_PANEL) {
            map.panelUp(id, lastUpX, lastUpY);
            return;
        }
        if (t == T_SCREEN) {
            if (pickPrompt == null) host.onScreenTapped();
            return;
        }
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
