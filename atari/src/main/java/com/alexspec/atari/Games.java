package com.alexspec.atari;

import com.alexspec.atari.core.AtariMachine;

/**
 * Programs bundled with the app (assets/demos). Only original works with redistribution rights
 * (ADR-021-ATARI): no commercial games, no original Atari ROMs. Each entry: file name in
 * assets/demos, author, hint shown before starting, and the controls it starts with.
 */
final class Games {
    static final class Game {
        final String file, title, author, hint;
        final int[] controls;

        Game(String file, String title, String author, String hint, int[] controls) {
            this.file = file;
            this.title = title;
            this.author = author;
            this.hint = hint;
            this.controls = controls;
        }
    }

    static final Game[] LIST = {
        new Game("catch-the-star.xex", "Поймай звезду", "написана для этого приложения, CC0",
            "Ведите букву A джойстиком к звёздочке — счёт в правом верхнем углу. Огонь — звук POKEY.\n\n"
            + "Программа крошечная (409 байт кода) — удобно смотреть на карте памяти: код с 2000, "
            + "список отображения ANTIC в 3000, экран с 4000; джойстик читается из D300, кнопка из D010, "
            + "звук пишется в D200–D201.",
            new int[]{AtariMachine.JOY_UP, AtariMachine.JOY_DOWN, AtariMachine.JOY_LEFT, AtariMachine.JOY_RIGHT,
                AtariMachine.FIRE, AtariMachine.START, AtariMachine.SPACE, AtariMachine.RETURN}),
    };

    private Games() {
    }

    static Game find(String file) {
        for (Game g : LIST) if (g.file.equals(file)) return g;
        return null;
    }

    /** the game's own layout, or null */
    static int[] controls(String file) {
        Game g = find(file);
        return g == null ? null : g.controls;
    }
}
