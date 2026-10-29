package com.alexspec.atari;

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

import com.alexspec.atari.core.AtariMachine;
import com.alexspec.atari.core.Dis6502;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the emulated screen and all on-screen controls (joystick, buttons, keyboard, toolbar)
 * and turns multi-touch input into Atari key presses and joystick moves. Ported from the ZX
 * app's MainView so both look and work the same. GPL-2.0-or-later, see atari/COPYING.
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

    private final AtariMachine machine;
    private final EmuThread emu;
    private final Host host;
    private final float dp;

    // configuration (set from the activity on the UI thread)
    ControlConfig config = new ControlConfig();
    int buttonCount = 3;
    boolean joy8;
    boolean vibrate = true;
    String title = "";
    /** Shown as an overlay; the emulation itself is paused by the activity. */
    boolean paused;
    /** speed shown over the screen (null at real speed) */
    String speedBadge;
    /** live memory map under the screen (portrait only) */
    boolean mapOn;
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
    /** beam and current instruction are shown until this time (ms) */
    private long infoUntil;

    /** show the beam and the current instruction for 4 seconds */
    void showMachineInfo() {
        infoUntil = System.currentTimeMillis() + 4000;
        invalidate();
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
    private final int[] joyCodes = {AtariKeys.NONE, AtariKeys.NONE, AtariKeys.NONE, AtariKeys.NONE};
    private boolean joyCtrl;
    private float knobX, knobY;
    private final int[] btnDown = new int[ControlConfig.MAX_BUTTONS];
    private final int[] btnCode = new int[ControlConfig.MAX_BUTTONS];
    private final int[] keyDownCount = new int[AtariKeys.COUNT];
    /** SHIFT and CONTROL: a tap latches the modifier for the next key */
    private static final int[] MODS = {AtariMachine.SHIFT, AtariMachine.CTRL};
    private final boolean[] modLatched = new boolean[2], modHeld = new boolean[2], modUsed = new boolean[2];

    // drawing
    private final Bitmap screenBmp = Bitmap.createBitmap(AtariMachine.WIDTH, AtariMachine.HEIGHT, Bitmap.Config.ARGB_8888);
    private final int[] pixels = new int[AtariMachine.WIDTH * AtariMachine.HEIGHT];
    private final Paint bmpPaint = new Paint();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect srcRect = new Rect(0, 0, AtariMachine.WIDTH, AtariMachine.HEIGHT);
    /** the picture is shown 4:3, as on a TV */
    private static final float ASPECT = 4f / 3f;

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
        java.util.Arrays.fill(btnCode, AtariKeys.NONE);
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

    /** The 800XL keyboard; the top row holds the console keys and the space bar. */
    private static final int[][] KBD_ROWS = {
        {AtariMachine.HELP, AtariMachine.START, AtariMachine.SELECT, AtariMachine.OPTION, AtariMachine.SPACE,
            AtariMachine.RESET, AtariMachine.BREAK},
        {0x1C, 0x1F, 0x1E, 0x1A, 0x18, 0x1D, 0x1B, 0x33, 0x35, 0x30, 0x32, 0x36, 0x37, 0x34},  // ESC 1..0 < > DEL
        {0x2C, 0x2F, 0x2E, 0x2A, 0x28, 0x2D, 0x2B, 0x0B, 0x0D, 0x08, 0x0A, 0x0E, 0x0F, 0x0C},  // TAB Q..P - = RETURN
        {AtariMachine.CTRL, 0x3F, 0x3E, 0x3A, 0x38, 0x3D, 0x39, 0x01, 0x05, 0x00, 0x02, 0x06, 0x07, 0x3C}, // CTRL A..L ; + * CAPS
        {AtariMachine.SHIFT, 0x17, 0x16, 0x12, 0x10, 0x15, 0x23, 0x25, 0x20, 0x22, 0x26, 0x27},  // SHIFT Z..M , . / inverse
    };

    private static boolean isConsole(int code) {
        return code >= AtariMachine.BREAK && code <= AtariMachine.RESET || code == AtariMachine.HELP;
    }

    private void buildKeyboard() {
        rows.clear();
        for (int[] r : KBD_ROWS) {
            List<VKey> line = new ArrayList<>();
            for (int code : r) {
                float w;
                if (code == AtariMachine.SPACE) w = 3f;
                else if (isConsole(code)) w = 1.6f;
                else if (code == AtariMachine.RETURN || code == AtariMachine.SHIFT) w = 1.5f;
                else if (code == AtariMachine.CTRL || code == AtariMachine.CAPS || code == AtariMachine.TAB) w = 1.25f;
                else w = 1f;
                line.add(new VKey(code, w));
            }
            rows.add(line);
        }
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
        for (int c = 0; c < AtariKeys.COUNT; c++) {
            while (keyDownCount[c] > 0) {
                keyDownCount[c]--;
                machine.keyUp(c);
            }
        }
        for (int m = 0; m < 2; m++) {
            if (modLatched[m]) machine.keyUp(MODS[m]);
            modLatched[m] = modHeld[m] = modUsed[m] = false;
        }
        if (joyCtrl) {
            machine.keyUp(AtariMachine.CTRL);
            joyCtrl = false;
        }
        invalidate();
    }

    void configChanged() {
        releaseAll();
        layoutControls(getWidth(), getHeight());
        invalidate();
    }

    // ------------------------------------------------------------------ layout

    @Override
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
            float sh = w / ASPECT;
            screenRect.set(ox, oy, ox + w, oy + sh);
            toolbarY = oy + sh;
            menuRect.set(ox + 8 * dp, toolbarY + (tb - small) / 2, ox + 8 * dp + small * 1.4f, toolbarY + (tb + small) / 2);
            kbdBtnRect.set(ox + w - 8 * dp - small * 1.4f, menuRect.top, ox + w - 8 * dp, menuRect.bottom);
            float top = toolbarY + tb;
            float bottom = oy + h;
            float regionH = bottom - top;
            joyZone.set(0, top, fullW / 2f, fullH);
            btnZone.set(fullW / 2f, top, fullW, fullH);
            mapVisible = mapOn;
            if (mapVisible) {
                // memory map right under the toolbar; controls float over it (semi-transparent)
                boolean bits = true;
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
            float rowH = Math.min(regionH / rows.size(), (mapVisible ? 42 : 58) * dp);
            kbdRect.set(ox, bottom - rowH * rows.size() - 6 * dp, ox + w, bottom);
            if (mapVisible && keyboardVisible && map.rect.bottom > kbdRect.top - 4 * dp)
                map.rect.bottom = Math.max(map.rect.top + 40 * dp, kbdRect.top - 4 * dp);
        } else {
            float aspect = ASPECT;
            float sw = Math.min(h * aspect, w * 0.64f);
            float sh = sw / aspect;
            float sx = ox + (w - sw) / 2f;
            screenRect.set(sx, oy + (h - sh) / 2f, sx + sw, oy + (h + sh) / 2f);
            toolbarY = -1;
            mapVisible = false;
            menuRect.set(ox + 6 * dp, oy + 6 * dp, ox + 6 * dp + small * 1.4f, oy + 6 * dp + small);
            kbdBtnRect.set(ox + w - 6 * dp - small * 1.4f, oy + 6 * dp, ox + w - 6 * dp, oy + 6 * dp + small);
            joyZone.set(0, menuRect.bottom + 8 * dp, sx, fullH);
            btnZone.set(sx + sw, menuRect.bottom + 8 * dp, fullW, fullH);
            float side = sx - ox;
            joyCx = ox + side / 2f;
            joyCy = oy + h * 0.62f;
            joyR = Math.min(side * 0.42f, h * 0.26f);
            layoutButtons(ox + w - side / 2f, oy + h * 0.62f, Math.min(side * 0.42f, h * 0.26f));
            float kh = Math.min(h * 0.62f, rows.size() * 62 * dp);
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
        float rowH = (kbdRect.bottom - top) / rows.size();
        float maxTotal = 0;
        for (List<VKey> r : rows) {
            float t = 0;
            for (VKey k : r) t += k.weight;
            maxTotal = Math.max(maxTotal, t);
        }
        float unit = (kbdRect.width() - 2 * pad) / maxTotal;
        for (int ri = 0; ri < rows.size(); ri++) {
            List<VKey> r = rows.get(ri);
            float total = 0;
            for (VKey k : r) total += k.weight;
            float x = kbdRect.left + pad + (kbdRect.width() - 2 * pad - unit * total) / 2f;
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
        screenBmp.setPixels(pixels, 0, AtariMachine.WIDTH, 0, 0, AtariMachine.WIDTH, AtariMachine.HEIGHT);
        c.drawBitmap(screenBmp, srcRect, screenRect, bmpPaint);
        // The picture stays clean on pause: the step buttons appear around it (toolbar / sides)
        // instead of a dark veil, and the registers only after a step (owner's request, UX-001).
        if (speedBadge != null) {
            String sp = speedBadge;
            text.setTextSize(13 * dp);
            float tw = text.measureText(sp) + 12 * dp;
            fill.setColor(0xCC3A5BD9);
            RectF b = new RectF(screenRect.right - tw - 6 * dp, screenRect.top + 6 * dp,
                    screenRect.right - 6 * dp, screenRect.top + 26 * dp);
            c.drawRoundRect(b, 8 * dp, 8 * dp, fill);
            text.setColor(Color.WHITE);
            c.drawText(sp, b.centerX(), b.centerY() + 5 * dp, text);
        }


        if (toolbarY >= 0) {
            fill.setColor(0xFF15151A);
            c.drawRect(0, toolbarY, getWidth(), toolbarY + toolbarH, fill);
            if (title != null && !title.isEmpty() && !(paused && pickPrompt == null)) {
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

        if (mapVisible) {
            map.programTitle = title;
            map.draw(c);
        }
        if (paused && pickPrompt == null) drawStepButtons(c);
        if (keyboardVisible) drawKeyboard(c);
        else if (!(mapVisible && map.isTutorialActive())) drawControls(c);
        if (System.currentTimeMillis() < infoUntil) drawMachineInfo(c); // on top of everything, 4 s after a step
    }

    private void drawUiButton(Canvas c, RectF r, String label, boolean active) {
        fill.setColor(active ? 0xCC3A5BD9 : 0x99303038);
        c.drawRoundRect(r, 10 * dp, 10 * dp, fill);
        text.setColor(Color.WHITE);
        text.setTextSize(r.height() * 0.5f);
        c.drawText(label, r.centerX(), r.centerY() + r.height() * 0.18f, text);
    }

    /**
     * Step buttons outside the picture: portrait — in the toolbar instead of the title;
     * landscape — two on each side of the picture, under ☰ and ⌨.
     */
    private void layoutStepButtons() {
        float gap = 6 * dp;
        if (toolbarY >= 0) {
            float left = menuRect.right + gap, right = kbdBtnRect.left - gap;
            float w = (right - left - gap * 3) / 4f;
            for (int i = 0; i < 4; i++) {
                float x = left + i * (w + gap);
                stepRects[i].set(x, menuRect.top, x + w, menuRect.bottom);
            }
        } else {
            float h = 44 * dp;
            for (int i = 0; i < 2; i++) {
                float y = menuRect.bottom + gap + i * (h + gap);
                stepRects[i].set(menuRect.left, y, screenRect.left - gap, y + h);
                stepRects[i + 2].set(screenRect.right + gap, y, kbdBtnRect.right, y + h);
            }
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

    /** beam position (crosshair) and the instruction at PC with the registers */
    private void drawMachineInfo(Canvas c) {
        int[] r = machine.regs;
        int[] b = machine.beam();
        float sx = screenRect.width() / AtariMachine.WIDTH, sy = screenRect.height() / AtariMachine.HEIGHT;
        if (b != null) {
            float x = screenRect.left + (b[0] + 0.5f) * sx, y = screenRect.top + (b[1] + 0.5f) * sy;
            stroke.setStrokeWidth(1.5f * dp);
            stroke.setColor(0xCCFF3030);
            c.drawLine(screenRect.left, y, screenRect.right, y, stroke);
            c.drawLine(x, screenRect.top, x, screenRect.bottom, stroke);
            fill.setColor(0xFFFFFF40);
            c.drawCircle(x, y, 3.5f * dp, fill);
        }
        int pc = r[5];
        int[] len = new int[1];
        String ins = Dis6502.dis(machine, pc, len);
        StringBuilder hex = new StringBuilder();
        for (int k = 0; k < len[0]; k++) hex.append(String.format("%02X", machine.rawRead(pc + k)));
        long t = machine.time() - machine.frames() * (long) AtariMachine.T_FRAME;
        int[] raw = machine.beamRaw();
        String l1 = String.format(java.util.Locale.US, "→ %04X  %-6s %s", pc, hex, ins);
        int lp = machine.lastInstrPc();
        String l0 = lp < 0 ? "" : String.format(java.util.Locale.US, "✓ %04X  %s", lp, Dis6502.dis(machine, lp, null));
        String l2 = String.format(java.util.Locale.US, "A %02X  X %02X  Y %02X  S %02X", r[0], r[1], r[2], r[3]);
        StringBuilder flags = new StringBuilder();
        String names = "NV-BDIZC";
        for (int k = 0; k < 8; k++) flags.append((r[4] & (0x80 >> k)) != 0 ? names.charAt(k) : '·');
        String l3 = String.format(java.util.Locale.US, "P %02X  %s", r[4], flags);
        String l4 = raw != null
                ? String.format(java.util.Locale.US, "кадр %d, такт %d (строка %d, %d)", machine.frames(), t, raw[0], raw[1])
                : String.format(java.util.Locale.US, "кадр %d, начало кадра", machine.frames());
        String[] lines = l0.isEmpty() ? new String[]{l1, l2, l3, l4} : new String[]{l0, l1, l2, l3, l4};
        android.graphics.Typeface old = text.getTypeface();
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setTextAlign(Paint.Align.LEFT);
        float ts = Math.min(13 * dp, screenRect.width() / 30f);
        text.setTextSize(ts);
        float lh = ts * 1.25f, pad = 5 * dp, maxW = 0;
        for (String l : lines) maxW = Math.max(maxW, text.measureText(l));
        // portrait: under the toolbar, off the picture; landscape: no room outside, top-left corner
        float bx = screenRect.left + 6 * dp, by = toolbarY >= 0 ? toolbarY + toolbarH + 6 * dp : screenRect.top + 6 * dp;
        fill.setColor(0xD0101018);
        c.drawRoundRect(bx, by, bx + maxW + 2 * pad, by + lh * lines.length + pad, 6 * dp, 6 * dp, fill);
        for (int i = 0; i < lines.length; i++) {
            boolean next = lines[i].startsWith("→"), done = lines[i].startsWith("✓");
            text.setColor(next ? 0xFFFFFF60 : done ? 0xFF20E8FF : 0xFFD0D0DA);
            c.drawText(lines[i], bx + pad, by + pad + (i + 0.8f) * lh, text);
        }
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(old);
        postInvalidateDelayed(Math.max(16, infoUntil - System.currentTimeMillis()));
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

    private static String shortLabel(int code) {
        return AtariKeys.shortLabel(code);
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
        float rad = 5 * dp;
        for (List<VKey> r : rows) {
            for (VKey k : r) {
                int mi = modIndex(k.code);
                boolean down = mi >= 0 ? (modLatched[mi] || modHeld[mi]) : keyDownCount[k.code] > 0;
                int bg;
                if (down) bg = 0xFF5A7DFF;
                else if (isConsole(k.code)) bg = 0xFF6A4A1A;      // console keys: brown-orange like the XL
                else if (mi >= 0 || k.code == AtariMachine.RETURN || k.code == AtariMachine.SPACE
                        || k.code == AtariMachine.CAPS || k.code == AtariMachine.TAB) bg = 0xFF3A3A48;
                else bg = 0xFF26262C;
                fill.setColor(bg);
                c.drawRoundRect(k.r, rad, rad, fill);
                String main = AtariKeys.label(k.code);
                String sh = AtariKeys.shifted(k.code);
                String ct = AtariKeys.control(k.code);
                boolean single = main.length() == 1;
                float kh = k.r.height(), kwd = k.r.width();
                text.setColor(Color.WHITE);
                float ts = single ? Math.min(kh * 0.42f, 20 * dp) : Math.min(kh * 0.26f, 12 * dp);
                text.setTextSize(ts);
                float tw = text.measureText(main);
                if (tw > kwd * 0.9f) text.setTextSize(ts * kwd * 0.9f / tw);
                text.setTextAlign(single ? Paint.Align.LEFT : Paint.Align.CENTER);
                float mx = single ? k.r.left + 3 * dp : k.r.centerX();
                float my = single ? k.r.top + kh * 0.5f : k.r.centerY() + text.getTextSize() * 0.36f;
                c.drawText(main, mx, my, text);
                // SHIFT symbol: upper right, red like ZX's symbols
                if (!sh.isEmpty()) {
                    text.setTextAlign(Paint.Align.RIGHT);
                    text.setColor(0xFFFF5A4A);
                    float ss = Math.min(kh * (sh.length() > 2 ? 0.18f : 0.26f), 11 * dp);
                    text.setTextSize(ss);
                    float sw = text.measureText(sh);
                    if (sw > kwd * 0.55f) text.setTextSize(ss * kwd * 0.55f / sw);
                    c.drawText(sh, k.r.right - 2.5f * dp, k.r.top + kh * 0.36f, text);
                }
                // CONTROL function (cursor keys): along the bottom, blue
                if (!ct.isEmpty()) {
                    text.setTextAlign(Paint.Align.CENTER);
                    text.setColor(0xFF8FB8FF);
                    float ls = Math.min(kh * 0.24f, 11 * dp);
                    text.setTextSize(ls);
                    c.drawText(ct, k.r.centerX(), k.r.bottom - 3 * dp, text);
                }
                text.setTextAlign(Paint.Align.CENTER);
            }
        }
    }

    // ------------------------------------------------------------------ touch

    private int hitTest(float x, float y) {
        if (inflate(menuRect, 8 * dp).contains(x, y)) return T_MENU;
        if (inflate(kbdBtnRect, 8 * dp).contains(x, y)) return T_KBD;
        if (paused && pickPrompt == null) {
            layoutStepButtons();
            for (int i = 0; i < 4; i++) if (stepRects[i].contains(x, y)) return T_STEP + i;
        }
        if (screenRect.contains(x, y) && !(keyboardVisible && kbdRect.contains(x, y))) return T_SCREEN;
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
            int mi = modIndex(code);
            if (mi >= 0) {
                if (modLatched[mi]) {
                    modLatched[mi] = false;
                    machine.keyUp(code);
                    pointers.put(id, T_CONSUMED);
                } else {
                    modLatched[mi] = true;
                    modHeld[mi] = true;
                    modUsed[mi] = false;
                    machine.keyDown(code);
                }
            } else {
                keyDownCount[code]++;
                machine.keyDown(code);
                for (int m = 0; m < 2; m++) if (modLatched[m]) modUsed[m] = true;
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
            int mi = modIndex(code);
            if (mi >= 0) {
                modHeld[mi] = false;
                if (modUsed[mi] && modLatched[mi]) {
                    modLatched[mi] = false;
                    machine.keyUp(code);
                }
            } else if (keyDownCount[code] > 0) {
                keyDownCount[code]--;
                machine.keyUp(code);
                for (int m = 0; m < 2; m++) {
                    if (modLatched[m] && !modHeld[m]) {
                        modLatched[m] = false;
                        machine.keyUp(MODS[m]);
                    }
                }
            }
            invalidate();
        }
    }

    private static int modIndex(int code) {
        return code == AtariMachine.SHIFT ? 0 : code == AtariMachine.CTRL ? 1 : -1;
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
            btnCode[i] = AtariKeys.NONE;
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
                joyCodes[i] = AtariKeys.NONE;
            }
        }
        // Atari cursor keys are CONTROL + - = + *: hold CONTROL while a direction is held
        boolean wantCtrl = config.ctrlArrows && dirs != 0;
        if (wantCtrl != joyCtrl) {
            if (wantCtrl) machine.keyDown(AtariMachine.CTRL);
            else machine.keyUp(AtariMachine.CTRL);
            joyCtrl = wantCtrl;
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
        if (code == AtariKeys.NONE) return;
        boolean ctrl = config.ctrlArrows && slot < ControlConfig.BUTTON0;
        if (down) {
            if (ctrl) machine.keyDown(AtariMachine.CTRL);
            machine.keyDown(code);
        } else {
            machine.keyUp(code);
            if (ctrl) machine.keyUp(AtariMachine.CTRL);
        }
    }
}
