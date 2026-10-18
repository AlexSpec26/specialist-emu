package com.alexspec.specialist;

import android.content.SharedPreferences;

import com.alexspec.specialist.core.SpecKeys;

/**
 * Which Specialist key each on-screen control presses. Profiles are stored per program
 * (by file name), with a "default" profile used for everything else.
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

    final int[] keys = new int[SLOTS];

    ControlConfig() {
        setDefaults();
    }

    void setDefaults() {
        keys[UP] = SpecKeys.UP;
        keys[DOWN] = SpecKeys.DOWN;
        keys[LEFT] = SpecKeys.LEFT;
        keys[RIGHT] = SpecKeys.RIGHT;
        keys[BUTTON0] = SpecKeys.SPACE;
        keys[BUTTON0 + 1] = SpecKeys.ENTER;
        keys[BUTTON0 + 2] = SpecKeys.ESC;
        keys[BUTTON0 + 3] = SpecKeys.byLabel("F1");
    }

    void copyFrom(ControlConfig o) {
        System.arraycopy(o.keys, 0, keys, 0, SLOTS);
    }

    private static String prefKey(String profile) {
        return "map:" + profile;
    }

    /** Loads a profile; falls back to the default profile, then to built-in defaults. */
    static ControlConfig load(SharedPreferences p, String profile) {
        ControlConfig c = new ControlConfig();
        String s = p.getString(prefKey(profile), null);
        if (s == null && !DEFAULT_PROFILE.equals(profile)) s = p.getString(prefKey(DEFAULT_PROFILE), null);
        if (s != null) {
            String[] parts = s.split(",");
            for (int i = 0; i < SLOTS && i < parts.length; i++) {
                try {
                    int v = Integer.parseInt(parts[i].trim());
                    c.keys[i] = (v >= SpecKeys.NONE && v < SpecKeys.COUNT) ? v : SpecKeys.NONE;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return c;
    }

    static boolean hasOwn(SharedPreferences p, String profile) {
        return p.contains(prefKey(profile));
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
