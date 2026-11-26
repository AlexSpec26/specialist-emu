package com.alexspec.atari;

import com.alexspec.atari.core.AtariMachine;

import java.util.Locale;

/**
 * Emulation speed steps. ATARI-009: fine steps for playing down to 1/16 (owner: "более дробный
 * счётчик замедления — 2/3, 3/4 … больше 16 замедлять и не надо"); below that the old halving
 * steps to 1 cycle per second stay for watching the code (ATARI-007), past the playing ones.
 */
final class Speed {
    /** speed as a fraction num/den of the real one; the last step is 1 cycle per second */
    private static final int[][] FRACTIONS = {
        {4, 1}, {2, 1}, {1, 1},
        {3, 4}, {2, 3}, {1, 2}, {1, 3}, {1, 4}, {1, 5}, {1, 6}, {1, 8}, {1, 10}, {1, 12}, {1, 16},
        {1, 32}, {1, 64}, {1, 128}, {1, 256}, {1, 512}, {1, 1024}, {1, 2048}, {1, 4096},
        {1, 8192}, {1, 16384}, {1, 32768}, {1, 65536}, {1, 131072}, {1, 262144}, {1, 524288},
        {1, 1048576},
    };
    static final int STEPS = FRACTIONS.length + 1;
    /** step of the real speed */
    static final int REAL = 2;
    /** the last of the playing steps (1/16); past it, for watching the code */
    static final int LAST_PLAYING = 13;

    private Speed() {
    }

    static double factor(int step) {
        step = Math.max(0, Math.min(STEPS - 1, step));
        if (step == STEPS - 1) return 1.0 / AtariMachine.CPU_HZ;
        return FRACTIONS[step][0] / (double) FRACTIONS[step][1];
    }

    /** the step nearest to a speed factor (old settings, ATARI-009) */
    static int nearest(double factor) {
        int best = REAL;
        double bd = Double.MAX_VALUE;
        for (int s = 0; s < STEPS; s++) {
            double d = Math.abs(Math.log(factor(s) / factor));
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    /** cycles per second at this step */
    static double tps(int step) {
        return factor(step) * AtariMachine.CPU_HZ;
    }

    private static String fraction(int step) {
        int[] f = FRACTIONS[step];
        return f[1] == 1 ? "×" + f[0] : f[0] + "/" + f[1];
    }

    /** short text for the badge beside the picture; null at real speed */
    static String badge(int step) {
        step = Math.max(0, Math.min(STEPS - 1, step));
        if (step == REAL) return null;
        double t = tps(step);
        if (step < STEPS - 1 && t >= 1000) return fraction(step);
        return String.format(Locale.US, t >= 10 ? "%.0f т/с" : "%.1f т/с", t).replace(".0 т/с", " т/с");
    }

    /** longer description for the menu and the slider */
    static String describe(int step) {
        step = Math.max(0, Math.min(STEPS - 1, step));
        if (step == REAL) return "реальная скорость (1,77 млн тактов/с)";
        if (step < REAL) return fraction(step) + " — быстрее настоящего";
        double t = tps(step);
        String rate;
        if (t >= 1e6) rate = String.format(Locale.US, "%.2f млн тактов/с", t / 1e6);
        else if (t >= 1e3) rate = String.format(Locale.US, "%.1f тыс. тактов/с", t / 1e3);
        else rate = String.format(Locale.US, "%.1f такта/с", t);
        double frame = AtariMachine.T_FRAME / t;
        String f = frame < 120 ? String.format(Locale.US, "кадр за %.2f с", frame) : "кадр за " + human(frame);
        String head = step == STEPS - 1 ? "1 такт/с" : fraction(step);
        String tail = step > LAST_PLAYING ? " (смотреть код)" : "";
        return head + " — " + rate + ", " + f + tail;
    }

    private static String human(double sec) {
        if (sec < 7200) return String.format(Locale.US, "%.0f мин", sec / 60);
        return String.format(Locale.US, "%.1f ч", sec / 3600);
    }
}
