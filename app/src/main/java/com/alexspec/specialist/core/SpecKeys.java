package com.alexspec.specialist.core;

/**
 * The Specialist keyboard: a 12 x 6 matrix plus a separate Shift (НР) line.
 * A key code is {@code column * 6 + row}; {@link #SHIFT} is the extra Shift key.
 * Columns are driven by PPI port A (bits 0-7) and port C (bits 0-3),
 * rows are read on PPI port B bits 2-7.
 */
public final class SpecKeys {
    public static final int COLS = 12;
    public static final int ROWS = 6;
    public static final int SHIFT = COLS * ROWS; // 72
    public static final int COUNT = SHIFT + 1;
    public static final int NONE = -1;

    /** Main label shown on the key (Cyrillic where the key has one). */
    private static final String[][] LABEL = {
        {"ВК", "ЗБ", ".", ":", "-", "СТР"},
        {"ПС", "/", "Э", "Х", "0", "F11"},
        {"→", ",", "Ж", "З", "9", "F10"},
        {"ПВ", "Ю", "Д", "Щ", "8", "F9"},
        {"←", "Б", "Л", "Ш", "7", "F8"},
        {"Пробел", "Ь", "О", "Г", "6", "F7"},
        {"АР2", "Т", "Р", "Н", "5", "F6"},
        {"ТАБ", "И", "П", "Е", "4", "F5"},
        {"↓", "М", "А", "К", "3", "F4"},
        {"↑", "С", "В", "У", "2", "F3"},
        {"↖", "Ч", "Ы", "Ц", "1", "F2"},
        {"Р/Л", "Я", "Ф", "Й", ";", "F1"},
    };

    /** Secondary (Latin / shifted) label, may be empty. */
    private static final String[][] LABEL2 = {
        {"", "", ">", "*", "=", ""},
        {"", "?", "\\", "H", "", ""},
        {"", "<", "V", "Z", ")", ""},
        {"", "@", "D", "]", "(", ""},
        {"", "B", "L", "[", "'", ""},
        {"", "X", "O", "G", "&", ""},
        {"", "T", "R", "N", "%", ""},
        {"", "I", "P", "E", "$", ""},
        {"", "M", "A", "K", "#", ""},
        {"", "S", "W", "U", "\"", ""},
        {"", "^", "Y", "C", "!", ""},
        {"", "Q", "F", "J", "+", ""},
    };

    private SpecKeys() {
    }

    public static int code(int col, int row) {
        return col * ROWS + row;
    }

    public static String label(int code) {
        if (code == SHIFT) return "НР";
        if (code < 0 || code >= SHIFT) return "—";
        return LABEL[code / ROWS][code % ROWS];
    }

    public static String label2(int code) {
        if (code < 0 || code >= SHIFT) return "";
        return LABEL2[code / ROWS][code % ROWS];
    }

    /** Human readable name for menus, e.g. "Й (J)". */
    public static String fullName(int code) {
        String l2 = label2(code);
        return l2.isEmpty() ? label(code) : label(code) + "  " + l2;
    }

    /** Finds a key by its main label; returns NONE if absent. */
    public static int byLabel(String label) {
        if ("НР".equals(label)) return SHIFT;
        for (int c = 0; c < COLS; c++)
            for (int r = 0; r < ROWS; r++)
                if (LABEL[c][r].equals(label)) return code(c, r);
        return NONE;
    }

    /** Finds a key by its secondary (Latin / shifted) label; returns NONE if absent. */
    public static int byLabel2(String label) {
        for (int c = 0; c < COLS; c++)
            for (int r = 0; r < ROWS; r++)
                if (LABEL2[c][r].equals(label)) return code(c, r);
        return NONE;
    }

    public static final int ENTER = code(0, 0);
    public static final int BACKSPACE = code(0, 1);
    public static final int CLEAR = code(0, 5);
    public static final int LF = code(1, 0);
    public static final int RIGHT = code(2, 0);
    public static final int RPT = code(3, 0);
    public static final int LEFT = code(4, 0);
    public static final int SPACE = code(5, 0);
    public static final int ESC = code(6, 0);
    public static final int TAB = code(7, 0);
    public static final int DOWN = code(8, 0);
    public static final int UP = code(9, 0);
    public static final int HOME = code(10, 0);
    public static final int LANG = code(11, 0);
}
