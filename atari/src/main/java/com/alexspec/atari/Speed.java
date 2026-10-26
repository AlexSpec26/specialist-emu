package com.alexspec.atari;

import com.alexspec.atari.core.AtariMachine;

import java.util.Locale;

/** Emulation speed steps: each step halves the speed, from x4 down to 1 cycle per second (as in ZX). */
final class Speed {
    static final int STEPS = 24;
    /** step of the real speed */
    static final int REAL = 2;

    private Speed() {
    }

    static double factor(int step) {
        step = Math.max(0, Math.min(STEPS - 1, step));
        if (step == STEPS - 1) return 1.0 / AtariMachine.CPU_HZ;
        return Math.pow(2, REAL - step);
    }

    /** cycles per second at this step */
    static double tps(int step) {
        return factor(step) * AtariMachine.CPU_HZ;
    }

    /** short text for the badge on the screen; null at real speed */
    static String badge(int step) {
        if (step == REAL) return null;
        if (step < REAL) return "×" + (1 << (REAL - step));
        double t = tps(step);
        if (t >= 1000) return "1/" + (1L << (step - REAL));
        return String.format(Locale.US, t >= 10 ? "%.0f т/с" : "%.1f т/с", t).replace(".0 т/с", " т/с");
    }

    /** longer description for the menu and the slider */
    static String describe(int step) {
        if (step == REAL) return "реальная скорость (1,77 млн тактов/с)";
        if (step < REAL) return "×" + (1 << (REAL - step)) + " — быстрее настоящего";
        double t = tps(step);
        String rate;
        if (t >= 1e6) rate = String.format(Locale.US, "%.2f млн тактов/с", t / 1e6);
        else if (t >= 1e3) rate = String.format(Locale.US, "%.1f тыс. тактов/с", t / 1e3);
        else rate = String.format(Locale.US, "%.1f такта/с", t);
        double frame = AtariMachine.T_FRAME / t;
        String f = frame < 120 ? String.format(Locale.US, "кадр за %.2f с", frame) : "кадр за " + human(frame);
        return (step == STEPS - 1 ? "1 такт/с" : "1/" + (1L << (step - REAL))) + " — " + rate + ", " + f;
    }

    private static String human(double sec) {
        if (sec < 7200) return String.format(Locale.US, "%.0f мин", sec / 60);
        return String.format(Locale.US, "%.1f ч", sec / 3600);
    }
}
