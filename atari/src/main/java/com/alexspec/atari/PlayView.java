package com.alexspec.atari;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Atari screen plus on-screen controls: console keys (START/SELECT/OPTION), and either a
 * joystick with FIRE or a small Atari keyboard. Multi-touch; also merges physical keyboard and
 * gamepad input passed in by {@link AtariActivity}.
 *
 * Input goes to the engine as libretro codes: joypad bits in {@link AtariRunner#buttons}, keys
 * (RETROK_*) in {@link AtariRunner#keys}. The Atari800 core maps the joypad to the Atari
 * joystick and console keys.
 * GPL-2.0-or-later, see atari/COPYING.
 */
final class PlayView extends View {
    // libretro joypad bits (RETRO_DEVICE_ID_JOYPAD_*), as the Atari800 core interprets them.
    static final int BIT_FIRE = 0;    // B
    static final int BIT_SELECT = 2;  // SELECT
    static final int BIT_START = 3;   // START
    static final int BIT_UP = 4, BIT_DOWN = 5, BIT_LEFT = 6, BIT_RIGHT = 7;
    static final int BIT_OPTION = 10; // L

    // libretro key codes (RETROK_*).
    static final int KEY_BACKSPACE = 8, KEY_TAB = 9, KEY_RETURN = 13, KEY_ESCAPE = 27, KEY_SPACE = 32;
    static final int KEY_UP = 273, KEY_DOWN = 274, KEY_RIGHT = 275, KEY_LEFT = 276;
    static final int KEY_SHIFT = 304, KEY_CTRL = 306;

    /**
     * What a touch on a control does: values 0..15 are joypad bits, KEY_BASE + code is a key,
     * JOY is the joystick area, NONE is empty space.
     */
    private static final int KEY_BASE = 1000, JOY = -1, NONE = -99;

    boolean keyboard, paused;
    private boolean shift, ctrl;

    private final AtariRunner runner;
    private final float dp;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), pixelPaint = new Paint();
    private Bitmap bitmap;
    private final int[] pixels = new int[NativeAtari.MAX_PIXELS];
    private final RectF screenRect = new RectF(), joy = new RectF(), fire = new RectF();
    private final List<Control> controls = new ArrayList<>();
    private final SparseArray<Touch> touches = new SparseArray<>();
    private int physicalButtons, analogButtons;
    private final int[] physicalKeys = new int[NativeAtari.KEYS];

    private static final class Control {
        final RectF rect;
        final String label;
        final int action;

        Control(RectF rect, String label, int action) {
            this.rect = rect;
            this.label = label;
            this.action = action;
        }
    }

    private static final class Touch {
        int action;
        float x, y;

        Touch(int action, float x, float y) {
            this.action = action;
            this.x = x;
            this.y = y;
        }
    }

    private static final String[][] KEYBOARD_ROWS = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"},
            {"A", "S", "D", "F", "G", "H", "J", "K", "L", "⌫"},
            {"SHIFT", "Z", "X", "C", "V", "B", "N", "M", ",", "."},
            {"CTRL", "TAB", "SPACE", ";", "=", "+", "←", "→", "↑", "↓"},
    };

    PlayView(Context context, AtariRunner runner) {
        super(context);
        this.runner = runner;
        dp = getResources().getDisplayMetrics().density;
        setFocusableInTouchMode(true);
        pixelPaint.setFilterBitmap(false);
    }

    // ---- layout ----

    private void layoutControls() {
        controls.clear();
        float w = getWidth(), h = getHeight();
        float consoleHeight = 36 * dp;
        float keyboardHeight = keyboard ? Math.min(h * 0.47f, 250 * dp) : 0;
        boolean landscape = w > h * 1.2f;
        float controlHeight = keyboard ? keyboardHeight
                : landscape ? Math.min(h * 0.45f, 150 * dp) : Math.min(h * 0.38f, 200 * dp);

        // Screen: 4:3, as large as fits. In landscape the joystick overlaps the screen sides.
        float available = landscape && !keyboard ? h - consoleHeight : h - consoleHeight - controlHeight;
        float sh = Math.min(available, w * 0.75f), sw = sh * 4 / 3;
        screenRect.set((w - sw) / 2, 0, (w + sw) / 2, sh);

        // Row of console keys under the screen.
        float cy = landscape && !keyboard ? h - consoleHeight : h - controlHeight - consoleHeight;
        String[] labels = {"START", "SELECT", "OPTION", "ESC", "RETURN"};
        int[] actions = {BIT_START, BIT_SELECT, BIT_OPTION, KEY_BASE + KEY_ESCAPE, KEY_BASE + KEY_RETURN};
        for (int i = 0; i < labels.length; i++) {
            float x = i * w / labels.length;
            controls.add(new Control(new RectF(x + 2 * dp, cy + 2 * dp, x + w / labels.length - 2 * dp,
                    cy + consoleHeight - 2 * dp), labels[i], actions[i]));
        }

        if (keyboard) {
            float top = h - keyboardHeight, kh = keyboardHeight / KEYBOARD_ROWS.length;
            for (int row = 0; row < KEYBOARD_ROWS.length; row++) {
                String[] keys = KEYBOARD_ROWS[row];
                float kw = w / keys.length;
                for (int col = 0; col < keys.length; col++) {
                    RectF r = new RectF(col * kw + dp, top + row * kh + dp, (col + 1) * kw - dp, top + (row + 1) * kh - dp);
                    controls.add(new Control(r, keys[col], KEY_BASE + keyCode(keys[col])));
                }
            }
            joy.setEmpty();
            fire.setEmpty();
        } else {
            float size = Math.min(controlHeight * 0.92f, 180 * dp);
            float bottom = landscape ? h - consoleHeight - 4 * dp : h - 4 * dp;
            joy.set(8 * dp, bottom - size, 8 * dp + size, bottom);
            float fs = size * 0.62f;
            fire.set(w - fs - 16 * dp, bottom - fs - 8 * dp, w - 16 * dp, bottom - 8 * dp);
        }
    }

    private static int keyCode(String label) {
        switch (label) {
            case "SHIFT": return KEY_SHIFT;
            case "CTRL": return KEY_CTRL;
            case "⌫": return KEY_BACKSPACE;
            case "TAB": return KEY_TAB;
            case "SPACE": return KEY_SPACE;
            case "←": return KEY_LEFT;
            case "→": return KEY_RIGHT;
            case "↑": return KEY_UP;
            case "↓": return KEY_DOWN;
            default: return Character.toLowerCase(label.charAt(0));
        }
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xff101018);
        layoutControls();

        int w, h;
        synchronized (runner.screen) {
            w = runner.width;
            h = runner.height;
            System.arraycopy(runner.screen, 0, pixels, 0, w * h);
        }
        if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
        canvas.drawBitmap(bitmap, null, screenRect, pixelPaint);

        paint.setTextAlign(Paint.Align.CENTER);
        for (Control c : controls) {
            boolean latched = (c.action == KEY_BASE + KEY_SHIFT && shift) || (c.action == KEY_BASE + KEY_CTRL && ctrl);
            paint.setColor(latched ? 0xff5277df : 0xee293348);
            canvas.drawRoundRect(c.rect, 5 * dp, 5 * dp, paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(Math.min(13 * dp, c.rect.width() / Math.max(1, c.label.length()) * 1.4f));
            canvas.drawText(c.label, c.rect.centerX(), c.rect.centerY() + paint.getTextSize() * 0.35f, paint);
        }
        if (!keyboard) {
            paint.setColor(0xcc293348);
            canvas.drawOval(joy, paint);
            canvas.drawOval(fire, paint);
            paint.setColor(0xffdbe4ff);
            paint.setTextSize(34 * dp);
            canvas.drawText("✚", joy.centerX(), joy.centerY() + 12 * dp, paint);
            paint.setTextSize(18 * dp);
            canvas.drawText("FIRE", fire.centerX(), fire.centerY() + 6 * dp, paint);
        }
        if (paused) {
            paint.setColor(0x99000000);
            canvas.drawRect(screenRect, paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(24 * dp);
            canvas.drawText("ПАУЗА", screenRect.centerX(), screenRect.centerY(), paint);
        }
    }

    // ---- touch ----

    private int hit(float x, float y) {
        for (Control c : controls) if (c.rect.contains(x, y)) return c.action;
        if (joy.contains(x, y)) return JOY;
        if (fire.contains(x, y)) return BIT_FIRE;
        return NONE;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked(), index = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = e.getX(index), y = e.getY(index);
                int hit = hit(x, y);
                // SHIFT and CTRL latch until the next key is released.
                if (hit == KEY_BASE + KEY_SHIFT) shift = !shift;
                else if (hit == KEY_BASE + KEY_CTRL) ctrl = !ctrl;
                else touches.put(e.getPointerId(index), new Touch(hit, x, y));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    Touch t = touches.get(e.getPointerId(i));
                    if (t == null) continue;
                    t.x = e.getX(i);
                    t.y = e.getY(i);
                    // A finger on the joystick keeps steering even outside its circle;
                    // on other controls it can slide from one to the next.
                    if (t.action != JOY) t.action = hit(t.x, t.y);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                Touch t = touches.get(e.getPointerId(index));
                if (t != null && t.action >= KEY_BASE) shift = ctrl = false;
                touches.remove(e.getPointerId(index));
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                touches.clear();
                shift = ctrl = false;
                break;
        }
        updateInput();
        invalidate();
        return true;
    }

    /** Combines touches, physical keys and gamepad into the runner's input state. */
    private void updateInput() {
        int mask = physicalButtons | analogButtons;
        for (int i = 0; i < NativeAtari.KEYS; i++) runner.keys.set(i, physicalKeys[i]);
        if (shift) runner.keys.set(KEY_SHIFT, 1);
        if (ctrl) runner.keys.set(KEY_CTRL, 1);
        for (int i = 0; i < touches.size(); i++) {
            Touch t = touches.valueAt(i);
            if (t.action == JOY) {
                float dx = (t.x - joy.centerX()) / (joy.width() / 2), dy = (t.y - joy.centerY()) / (joy.height() / 2);
                if (dx < -.25f) mask |= 1 << BIT_LEFT;
                if (dx > .25f) mask |= 1 << BIT_RIGHT;
                if (dy < -.25f) mask |= 1 << BIT_UP;
                if (dy > .25f) mask |= 1 << BIT_DOWN;
            } else if (t.action >= KEY_BASE && t.action < KEY_BASE + NativeAtari.KEYS) {
                runner.keys.set(t.action - KEY_BASE, 1);
            } else if (t.action >= 0 && t.action < 16) {
                mask |= 1 << t.action;
            }
        }
        runner.buttons.set(mask);
    }

    // ---- physical input, from AtariActivity ----

    /** Analog stick or hat, each axis -1..1. */
    void analog(float x, float y) {
        analogButtons = (x < -.3f ? 1 << BIT_LEFT : 0) | (x > .3f ? 1 << BIT_RIGHT : 0)
                | (y < -.3f ? 1 << BIT_UP : 0) | (y > .3f ? 1 << BIT_DOWN : 0);
        updateInput();
    }

    void physicalButton(int bit, boolean down) {
        if (down) physicalButtons |= 1 << bit;
        else physicalButtons &= ~(1 << bit);
        updateInput();
    }

    void physicalKey(int key, boolean down) {
        if (key < 0 || key >= NativeAtari.KEYS) return;
        physicalKeys[key] = down ? 1 : 0;
        updateInput();
    }

    void releaseAll() {
        touches.clear();
        Arrays.fill(physicalKeys, 0);
        physicalButtons = analogButtons = 0;
        shift = ctrl = false;
        updateInput();
        invalidate();
    }
}
