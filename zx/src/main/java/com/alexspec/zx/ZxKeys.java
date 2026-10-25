package com.alexspec.zx;

import com.alexspec.zx.core.Spectrum48;

/**
 * Names of the ZX Spectrum keys and of the virtual Kempston keys. A key code is
 * half-row * 5 + bit, half-rows in port order (FEFE, FDFE ... 7FFE); 40..44 are the
 * Kempston joystick (right, left, down, up, fire).
 */
final class ZxKeys {
    static final int NONE = -1;
    static final int COUNT = Spectrum48.KEY_COUNT;

    // half-row by half-row, bit 0 first
    private static final String[] LABEL = {
        "CAPS", "Z", "X", "C", "V",
        "A", "S", "D", "F", "G",
        "Q", "W", "E", "R", "T",
        "1", "2", "3", "4", "5",
        "0", "9", "8", "7", "6",
        "P", "O", "I", "U", "Y",
        "ENTER", "L", "K", "J", "H",
        "SPACE", "SYM", "M", "N", "B",
        "K→", "K←", "K↓", "K↑", "K●",
    };

    /** BASIC keyword typed by the key in K mode (white, on the key itself) */
    private static final String[] KEYWORD = {
        "", "COPY", "CLEAR", "CONT", "CLS",
        "NEW", "SAVE", "DIM", "FOR", "GO TO",
        "PLOT", "DRAW", "REM", "RUN", "RAND",
        "", "", "", "", "",
        "", "", "", "", "",
        "PRINT", "POKE", "INPUT", "IF", "RETURN",
        "", "LET", "LIST", "LOAD", "GO SUB",
        "", "", "PAUSE", "NEXT", "BORDER",
        "", "", "", "", "",
    };

    /** symbol with SYMBOL SHIFT (red on the original keys) */
    private static final String[] SYMBOL = {
        "", ":", "£", "?", "/",
        "STOP", "NOT", "STEP", "TO", "THEN",
        "<=", "<>", ">=", "<", ">",
        "!", "@", "#", "$", "%",
        "_", ")", "(", "'", "&",
        "\"", ";", "AT", "OR", "AND",
        "", "=", "+", "-", "↑",
        "", "", ".", ",", "*",
        "", "", "", "", "",
    };

    /** CAPS SHIFT + digit: editing keys (above the digits on the original) */
    private static final String[] CAPS_DIGIT = {"DELETE", "EDIT", "CAPS LK", "TRUE V", "INV V", "←", "↓", "↑", "→", "GRAPH"};

    private ZxKeys() {
    }

    static String label(int code) {
        if (code < 0 || code >= COUNT) return "—";
        return LABEL[code];
    }

    static String keyword(int code) {
        return code < 0 || code >= KEYWORD.length ? "" : KEYWORD[code];
    }

    static String symbol(int code) {
        return code < 0 || code >= SYMBOL.length ? "" : SYMBOL[code];
    }

    /** text above a digit key (Caps Shift function), "" for other keys */
    static String capsFunction(int code) {
        String l = label(code);
        if (l.length() == 1 && Character.isDigit(l.charAt(0))) return CAPS_DIGIT[l.charAt(0) - '0'];
        return "";
    }

    static boolean isKempston(int code) {
        return code >= Spectrum48.KEMPSTON && code < COUNT;
    }

    /** for menus: "Q", "SPACE", "Kempston ↑" */
    static String fullName(int code) {
        if (code == NONE) return "—";
        if (isKempston(code)) {
            String[] n = {"Кемпстон →", "Кемпстон ←", "Кемпстон ↓", "Кемпстон ↑", "Кемпстон огонь"};
            return n[code - Spectrum48.KEMPSTON];
        }
        return label(code);
    }

    /** short label for the round buttons */
    static String shortLabel(int code) {
        if (code == NONE) return "—";
        if (code == Spectrum48.SPACE) return "␣";
        if (code == Spectrum48.ENTER) return "⏎";
        if (code == Spectrum48.KEMPSTON + 4) return "●";
        if (isKempston(code)) return label(code).substring(1);
        return label(code);
    }

    static int byLabel(String l) {
        for (int i = 0; i < COUNT; i++) if (LABEL[i].equalsIgnoreCase(l)) return i;
        return NONE;
    }
}
