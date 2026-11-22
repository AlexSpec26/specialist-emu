package com.alexspec.atari;

import android.content.SharedPreferences;

import com.alexspec.atari.core.AtariMachine;

/**
 * Which key (or joystick direction) each on-screen control presses. Profiles are stored per
 * program (by file name), with a "default" profile used for everything else. As in the ZX app.
 */
final class ControlConfig {
    static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3;
    static final int BUTTON0 = 4;
    static final int MAX_BUTTONS = 8;
    static final int SLOTS = BUTTON0 + MAX_BUTTONS;
    static final String DEFAULT_PROFILE = "";

    static final String[] SLOT_NAMES = {
        "Джойстик ↑", "Джойстик ↓", "Джойстик ←", "Джойстик →",
        "Кнопка 1", "Кнопка 2", "Кнопка 3", "Кнопка 4",
        "Кнопка 5", "Кнопка 6", "Кнопка 7", "Кнопка 8",
    };
    /** buttons 5-8 (ATARI-008; layouts and games only name 1-4): SELECT, OPTION, the keys 1 and 2 */
    private static final int[] EXTRA = {AtariMachine.SELECT, AtariMachine.OPTION, 0x1F, 0x1E};

    private static final int U = AtariMachine.JOY_UP, D = AtariMachine.JOY_DOWN, L = AtariMachine.JOY_LEFT,
            R = AtariMachine.JOY_RIGHT, F = AtariMachine.FIRE;

    /** ready-made layouts: name, then up, down, left, right, button 1..4 (5..8 from EXTRA) */
    static final String[] PRESET_NAMES = {
        "Джойстик: огонь, START, пробел, RETURN",
        "Джойстик: огонь, START, SELECT, OPTION",
        "Стрелки Atari (CTRL − = + *): пробел, RETURN, ESC, Y",
    };
    static final int[][] PRESETS = {
        {U, D, L, R, F, AtariMachine.START, AtariMachine.SPACE, AtariMachine.RETURN},
        {U, D, L, R, F, AtariMachine.START, AtariMachine.SELECT, AtariMachine.OPTION},
        {AtariMachine.MINUS, AtariMachine.EQUAL, AtariMachine.PLUS, AtariMachine.ASTERISK,
            AtariMachine.SPACE, AtariMachine.RETURN, AtariMachine.ESCAPE, 0x2B},
    };
    /** preset 2 needs CONTROL held with the direction keys */
    static final boolean[] PRESET_CTRL = {false, false, true};

    final int[] keys = new int[SLOTS];
    /** hold CONTROL together with the joystick directions (Atari cursor keys) */
    boolean ctrlArrows;

    ControlConfig() {
        setDefaults();
    }

    void setDefaults() {
        set(0);
    }

    void set(int preset) {
        fill(PRESETS[preset]);
        ctrlArrows = PRESET_CTRL[preset];
    }

    void set(int[] layout) {
        fill(layout);
        ctrlArrows = false;
    }

    private void fill(int[] layout) {
        int n = Math.min(layout.length, SLOTS);
        System.arraycopy(layout, 0, keys, 0, n);
        for (int i = n; i < SLOTS; i++) keys[i] = EXTRA[i - BUTTON0 - 4];
    }

    private static String prefKey(String profile) {
        return "map:" + profile;
    }

    /**
     * Loads a profile; falls back to the program's own built-in layout, then the default profile,
     * then the built-in defaults.
     */
    static ControlConfig load(SharedPreferences p, String profile) {
        ControlConfig c = new ControlConfig();
        String s = p.getString(prefKey(profile), null);
        if (s == null) {
            int[] own = Games.controls(profile);
            if (own != null) {
                c.set(own);
                return c;
            }
        }
        if (s == null && !DEFAULT_PROFILE.equals(profile)) s = p.getString(prefKey(DEFAULT_PROFILE), null);
        if (s != null) {
            // keys, then the CONTROL flag "c" / "-"; saves from before ATARI-008 have 4 buttons,
            // so their flag comes earlier and buttons 5-8 keep the defaults
            String[] parts = s.split(",");
            for (int i = 0; i < SLOTS && i < parts.length; i++) {
                String t = parts[i].trim();
                if (t.equals("c") || t.equals("-")) break;
                try {
                    int v = Integer.parseInt(t);
                    c.keys[i] = (v >= AtariKeys.NONE && v < AtariKeys.COUNT) ? v : AtariKeys.NONE;
                } catch (NumberFormatException ignored) {
                }
            }
            c.ctrlArrows = parts.length > 0 && "c".equals(parts[parts.length - 1].trim());
        }
        return c;
    }

    void save(SharedPreferences p, String profile) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SLOTS; i++) {
            if (i > 0) sb.append(',');
            sb.append(keys[i]);
        }
        sb.append(ctrlArrows ? ",c" : ",-");
        p.edit().putString(prefKey(profile), sb.toString()).apply();
    }

    static void remove(SharedPreferences p, String profile) {
        p.edit().remove(prefKey(profile)).apply();
    }
}
