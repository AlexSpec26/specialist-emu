package com.alexspec.atari;

import com.alexspec.atari.core.AtariMachine;

/**
 * Names of the Atari keys (codes as in {@link AtariMachine}: 0..63 keyboard keys by hardware
 * code, then SHIFT, CONTROL, BREAK, START, SELECT, OPTION, RESET and joystick 1). Plays the role
 * of ZxKeys in the ZX app.
 */
final class AtariKeys {
    static final int NONE = -1;
    static final int COUNT = AtariMachine.KEY_COUNT;

    /** label on the key, by hardware code; null = no key with this code on the 800XL */
    private static final String[] LABEL = new String[64];
    /** with SHIFT (shown small, top right) */
    private static final String[] SHIFTED = new String[64];
    /** with CONTROL: the cursor and editing functions (shown in blue) */
    private static final String[] CONTROL = new String[64];

    private static void key(int code, String label, String shifted, String control) {
        LABEL[code] = label;
        SHIFTED[code] = shifted;
        CONTROL[code] = control;
    }

    static {
        key(0x1C, "ESC", "", "");
        key(0x1F, "1", "!", ""); key(0x1E, "2", "\"", ""); key(0x1A, "3", "#", ""); key(0x18, "4", "$", "");
        key(0x1D, "5", "%", ""); key(0x1B, "6", "&", ""); key(0x33, "7", "'", ""); key(0x35, "8", "@", "");
        key(0x30, "9", "(", ""); key(0x32, "0", ")", "");
        key(0x36, "<", "CLR", ""); key(0x37, ">", "INS", ""); key(0x34, "DEL", "", "");
        key(0x2C, "TAB", "CLR", "SET");
        key(0x2F, "Q", "", ""); key(0x2E, "W", "", ""); key(0x2A, "E", "", ""); key(0x28, "R", "", "");
        key(0x2D, "T", "", ""); key(0x2B, "Y", "", ""); key(0x0B, "U", "", ""); key(0x0D, "I", "", "");
        key(0x08, "O", "", ""); key(0x0A, "P", "", "");
        key(0x0E, "-", "_", "↑"); key(0x0F, "=", "|", "↓"); key(0x0C, "RETURN", "", "");
        key(0x3F, "A", "", ""); key(0x3E, "S", "", ""); key(0x3A, "D", "", ""); key(0x38, "F", "", "");
        key(0x3D, "G", "", ""); key(0x39, "H", "", ""); key(0x01, "J", "", ""); key(0x05, "K", "", "");
        key(0x00, "L", "", "");
        key(0x02, ";", ":", ""); key(0x06, "+", "\\", "←"); key(0x07, "*", "^", "→"); key(0x3C, "CAPS", "", "");
        key(0x17, "Z", "", ""); key(0x16, "X", "", ""); key(0x12, "C", "", ""); key(0x10, "V", "", "");
        key(0x15, "B", "", ""); key(0x23, "N", "", ""); key(0x25, "M", "", "");
        key(0x20, ",", "[", ""); key(0x22, ".", "]", ""); key(0x26, "/", "?", ""); key(0x27, "Λ", "", "");
        key(0x21, "SPACE", "", ""); key(0x11, "HELP", "", "");
    }

    private AtariKeys() {
    }

    static boolean isKeyboard(int code) {
        return code >= 0 && code < 64 && LABEL[code] != null;
    }

    static boolean isJoystick(int code) {
        return code >= AtariMachine.JOY_UP && code <= AtariMachine.FIRE;
    }

    static String label(int code) {
        if (isKeyboard(code)) return LABEL[code];
        switch (code) {
            case AtariMachine.SHIFT: return "SHIFT";
            case AtariMachine.CTRL: return "CTRL";
            case AtariMachine.BREAK: return "BREAK";
            case AtariMachine.START: return "START";
            case AtariMachine.SELECT: return "SELECT";
            case AtariMachine.OPTION: return "OPTION";
            case AtariMachine.RESET: return "RESET";
            case AtariMachine.JOY_UP: return "Д↑";
            case AtariMachine.JOY_DOWN: return "Д↓";
            case AtariMachine.JOY_LEFT: return "Д←";
            case AtariMachine.JOY_RIGHT: return "Д→";
            case AtariMachine.FIRE: return "FIRE";
            default: return "—";
        }
    }

    static String shifted(int code) {
        return isKeyboard(code) ? SHIFTED[code] : "";
    }

    static String control(int code) {
        return isKeyboard(code) ? CONTROL[code] : "";
    }

    /** for menus: "Q", "SPACE", "Джойстик ↑" */
    static String fullName(int code) {
        if (code == NONE) return "—";
        switch (code) {
            case AtariMachine.JOY_UP: return "Джойстик ↑";
            case AtariMachine.JOY_DOWN: return "Джойстик ↓";
            case AtariMachine.JOY_LEFT: return "Джойстик ←";
            case AtariMachine.JOY_RIGHT: return "Джойстик →";
            case AtariMachine.FIRE: return "Огонь (FIRE)";
            default: return label(code);
        }
    }

    /** short label for the round buttons */
    static String shortLabel(int code) {
        if (code == NONE) return "—";
        if (code == AtariMachine.SPACE) return "␣";
        if (code == AtariMachine.RETURN) return "⏎";
        if (code == AtariMachine.FIRE) return "●";
        if (code == AtariMachine.START) return "STA";
        if (code == AtariMachine.SELECT) return "SEL";
        if (code == AtariMachine.OPTION) return "OPT";
        if (isJoystick(code)) return label(code).substring(1);
        return label(code);
    }

    /** keyboard key whose label is {@code l}, or NONE */
    static int byLabel(String l) {
        for (int i = 0; i < 64; i++) if (LABEL[i] != null && LABEL[i].equalsIgnoreCase(l)) return i;
        return NONE;
    }
}
