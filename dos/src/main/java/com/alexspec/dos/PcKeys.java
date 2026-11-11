package com.alexspec.dos;

import android.view.KeyEvent;

/** PC keys as libretro codes (RETROK_*), the on-screen keyboard rows and the Android key map. */
final class PcKeys {
    private PcKeys() {
    }

    static final int BACKSPACE = 8, TAB = 9, ENTER = 13, ESC = 27, SPACE = 32;
    static final int DELETE = 127, UP = 273, DOWN = 274, RIGHT = 275, LEFT = 276;
    static final int INSERT = 277, HOME = 278, END = 279, PAGEUP = 280, PAGEDOWN = 281;
    static final int F1 = 282, CAPS = 301, RSHIFT = 303, LSHIFT = 304, RCTRL = 305, LCTRL = 306;
    static final int RALT = 307, LALT = 308;

    /** a key of the on-screen keyboard: label, code, width in key units */
    static final class Key {
        final String label;
        final int code;
        final float w;

        Key(String label, int code, float w) {
            this.label = label;
            this.code = code;
            this.w = w;
        }
    }

    private static Key k(String label, int code) {
        return new Key(label, code, 1);
    }

    private static Key k(String label, int code, float w) {
        return new Key(label, code, w);
    }

    private static Key[] chars(String s) {
        Key[] r = new Key[s.length()];
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            r[i] = k(String.valueOf(Character.toUpperCase(c)), c);
        }
        return r;
    }

    private static Key[] row(Object... parts) {
        java.util.List<Key> l = new java.util.ArrayList<>();
        for (Object o : parts) {
            if (o instanceof Key) l.add((Key) o);
            else for (Key x : (Key[]) o) l.add(x);
        }
        return l.toArray(new Key[0]);
    }

    /** the on-screen PC keyboard, 6 rows of 15 units */
    static final Key[][] ROWS = {
            row(k("Esc", ESC, 1.25f), k("F1", F1), k("F2", F1 + 1), k("F3", F1 + 2), k("F4", F1 + 3), k("F5", F1 + 4),
                    k("F6", F1 + 5), k("F7", F1 + 6), k("F8", F1 + 7), k("F9", F1 + 8), k("F10", F1 + 9),
                    k("Ins", INSERT, 1.25f), k("Del", DELETE, 1.25f), k("PgUp", PAGEUP, 1.25f)),
            row(chars("`1234567890-="), k("⌫", BACKSPACE, 2)),
            row(k("Tab", TAB, 1.5f), chars("qwertyuiop[]"), k("\\", '\\', 1.5f)),
            row(k("Caps", CAPS, 1.75f), chars("asdfghjkl;'"), k("Enter", ENTER, 2.25f)),
            row(k("Shift", LSHIFT, 2.25f), chars("zxcvbnm,./"), k("↑", UP, 1.25f), k("Shift", RSHIFT, 1.5f)),
            row(k("Ctrl", LCTRL, 1.5f), k("Alt", LALT, 1.5f), k("Пробел", SPACE, 5.5f), k("Home", HOME, 1.25f),
                    k("End", END, 1.25f), k("←", LEFT), k("↓", DOWN), k("→", RIGHT), k("PgDn", PAGEDOWN, 1)),
    };

    static boolean isModifier(int code) {
        return code == LSHIFT || code == RSHIFT || code == LCTRL || code == RCTRL || code == LALT || code == RALT;
    }

    /** a hardware (Bluetooth / USB) keyboard key, or 0 */
    static int fromAndroid(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) return 'a' + keyCode - KeyEvent.KEYCODE_A;
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) return '0' + keyCode - KeyEvent.KEYCODE_0;
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) return F1 + keyCode - KeyEvent.KEYCODE_F1;
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: return ENTER;
            case KeyEvent.KEYCODE_ESCAPE: return ESC;
            case KeyEvent.KEYCODE_SPACE: return SPACE;
            case KeyEvent.KEYCODE_DEL: return BACKSPACE;
            case KeyEvent.KEYCODE_FORWARD_DEL: return DELETE;
            case KeyEvent.KEYCODE_TAB: return TAB;
            case KeyEvent.KEYCODE_DPAD_UP: return UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return RIGHT;
            case KeyEvent.KEYCODE_INSERT: return INSERT;
            case KeyEvent.KEYCODE_MOVE_HOME: return HOME;
            case KeyEvent.KEYCODE_MOVE_END: return END;
            case KeyEvent.KEYCODE_PAGE_UP: return PAGEUP;
            case KeyEvent.KEYCODE_PAGE_DOWN: return PAGEDOWN;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return LSHIFT;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return RSHIFT;
            case KeyEvent.KEYCODE_CTRL_LEFT: return LCTRL;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return RCTRL;
            case KeyEvent.KEYCODE_ALT_LEFT: return LALT;
            case KeyEvent.KEYCODE_ALT_RIGHT: return RALT;
            case KeyEvent.KEYCODE_CAPS_LOCK: return CAPS;
            case KeyEvent.KEYCODE_MINUS: return '-';
            case KeyEvent.KEYCODE_EQUALS: return '=';
            case KeyEvent.KEYCODE_LEFT_BRACKET: return '[';
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return ']';
            case KeyEvent.KEYCODE_BACKSLASH: return '\\';
            case KeyEvent.KEYCODE_SEMICOLON: return ';';
            case KeyEvent.KEYCODE_APOSTROPHE: return '\'';
            case KeyEvent.KEYCODE_GRAVE: return '`';
            case KeyEvent.KEYCODE_COMMA: return ',';
            case KeyEvent.KEYCODE_PERIOD: return '.';
            case KeyEvent.KEYCODE_SLASH: return '/';
            default: return 0;
        }
    }
}
