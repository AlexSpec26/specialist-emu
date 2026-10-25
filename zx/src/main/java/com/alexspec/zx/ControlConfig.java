package com.alexspec.zx;

import android.content.SharedPreferences;

import com.alexspec.zx.core.Spectrum48;

/**
 * Which key (or Kempston direction) each on-screen control presses. Profiles are stored per
 * program (by file name), with a "default" profile used for everything else.
 */
final class ControlConfig {
    static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3;
    static final int BUTTON0 = 4;
    static final int MAX_BUTTONS = 4;
    static final int SLOTS = BUTTON0 + MAX_BUTTONS;
    static final String DEFAULT_PROFILE = "";

    static final String[] SLOT_NAMES = {
        "Джойстик ↑", "Джойстик ↓", "Джойстик ←", "Джойстик →",
        "Кнопка 1", "Кнопка 2", "Кнопка 3", "Кнопка 4",
    };

    private static final int K = Spectrum48.KEMPSTON;

    /** ready-made layouts: name, then up, down, left, right, button 1..4 */
    static final String[] PRESET_NAMES = {
        "Кемпстон (огонь, пробел, ENTER, 1)",
        "Синклер, левый порт (9 8 6 7, огонь 0)",
        "Курсор / Protek (7 6 5 8, огонь 0)",
        "Клавиши Q A O P, пробел",
    };
    static final int[][] PRESETS = {
        {K + 3, K + 2, K + 1, K, K + 4, Spectrum48.SPACE, Spectrum48.ENTER, 15},
        {21, 22, 24, 23, 20, Spectrum48.SPACE, Spectrum48.ENTER, 15},
        {23, 24, 19, 22, 20, Spectrum48.SPACE, Spectrum48.ENTER, 15},
        {10, 5, 26, 25, Spectrum48.SPACE, 37, Spectrum48.ENTER, 15},
    };

    final int[] keys = new int[SLOTS];

    ControlConfig() {
        setDefaults();
    }

    void setDefaults() {
        System.arraycopy(PRESETS[0], 0, keys, 0, SLOTS);
    }

    void set(int[] preset) {
        System.arraycopy(preset, 0, keys, 0, SLOTS);
    }

    private static String prefKey(String profile) {
        return "map:" + profile;
    }

    /**
     * Loads a profile; falls back to the game's own built-in layout, then the default profile,
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
            String[] parts = s.split(",");
            for (int i = 0; i < SLOTS && i < parts.length; i++) {
                try {
                    int v = Integer.parseInt(parts[i].trim());
                    c.keys[i] = (v >= ZxKeys.NONE && v < ZxKeys.COUNT) ? v : ZxKeys.NONE;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return c;
    }

    void save(SharedPreferences p, String profile) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SLOTS; i++) {
            if (i > 0) sb.append(',');
            sb.append(keys[i]);
        }
        p.edit().putString(prefKey(profile), sb.toString()).apply();
    }

    static void remove(SharedPreferences p, String profile) {
        p.edit().remove(prefKey(profile)).apply();
    }
}
