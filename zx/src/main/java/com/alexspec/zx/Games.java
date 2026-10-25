package com.alexspec.zx;

import com.alexspec.zx.core.Spectrum48;

/**
 * Free games bundled with the app (assets/games). Mojon Twins games are distributed under
 * CC BY-NC-SA 3.0; "Lost in Worlds" by Nihirash is free to redistribute. Each entry: file
 * name in assets/games, author, hint shown before starting, and the controls it starts with.
 */
final class Games {
    static final class Game {
        final String file, author, hint;
        final int[] controls;

        Game(String file, String author, String hint, int[] controls) {
            this.file = file;
            this.author = author;
            this.hint = hint;
            this.controls = controls;
        }

        String title() {
            int dot = file.lastIndexOf('.');
            return dot > 0 ? file.substring(0, dot) : file;
        }
    }

    private static final int K = Spectrum48.KEMPSTON, SP = Spectrum48.SPACE, EN = Spectrum48.ENTER;
    private static final int Q = 10, A = 5, O = 26, P = 25, W = 11, E = 12, R = 13, D = 7, H = 34, G = 9, N = 38;
    private static final int D1 = 15, D2 = 16, D3 = 17;

    /** joystick = Kempston, buttons: fire, then the keys of the title menu */
    private static int[] kempston(int b2, int b3, int b4) {
        return new int[]{K + 3, K + 2, K + 1, K, K + 4, b2, b3, b4};
    }

    static final Game[] LIST = {
        new Game("Sir Ababol.tzx", "The Mojon Twins, 2010",
            "В меню нажмите 2 (Kempston) — кнопкой «2». Прыжок — огонь или ↑. Соберите 25 цветков ababol.",
            kempston(D2, D1, SP)),
        new Game("Sir Ababol DX.tap", "The Mojon Twins, 2013",
            "Расширенная версия. В меню 3 — Kempston (1 и 2 — клавиатура). Прыжок — огонь.",
            kempston(D3, D1, SP)),
        new Game("Lala Prologue.tzx", "The Mojon Twins, 2010",
            "Нажмите любую клавишу (кнопка «␣»). Если игра спросит управление — выберите Kempston. Соберите 25 зелий.",
            kempston(D2, D1, SP)),
        new Game("Cheril of the Bosque.tzx", "The Mojon Twins, 2010",
            "В меню 2 — Kempston. Ищите 13 гигантских орехов; красные ящики можно толкать.",
            kempston(D2, D1, SP)),
        new Game("Cheril Perils.tzx", "The Mojon Twins, 2010",
            "В меню 2 — Kempston. Раздавите 60 зомби прыжком сверху. Свиней можно толкать.",
            kempston(D2, D1, SP)),
        new Game("Cheril the Goddess.tap", "The Mojon Twins, 2010",
            "В меню 2 — Kempston. ↑ — лететь (утомляет), ↓ — взять или применить предмет, огонь — выстрел.",
            kempston(D2, D1, SP)),
        new Game("Horace Goes to the Tower.tzx", "The Mojon Twins, 2011",
            "В меню 2 — Kempston. Прыжок — огонь. Застряли — кнопка R перезапускает экран.",
            kempston(D2, R, D1)),
        new Game("Cadaverion.tap", "The Mojon Twins, 2013",
            "В меню 2 — Kempston. Движение + огонь — толкать статуи на плиты.",
            kempston(D2, D1, SP)),
        new Game("Columns.tzx", "The Mojon Twins, 2009",
            "Управление клавишами: O/P — влево/вправо, A — ускорить падение, пробел — повернуть, H — пауза.",
            new int[]{SP, A, O, P, SP, H, EN, D1}),
        new Game("Lost in Worlds.tap", "Nihirash, 2018",
            "Платформер. O/P — влево/вправо, пробел — прыжок.",
            new int[]{SP, A, O, P, SP, EN, D1, D2}),
        new Game("Judge Morry vs Baldo Midget.tzx", "The Mojon Twins, 2007",
            "Игрок 1: Q/W — влево/вправо, E — выстрел, R — прыжок. ENTER — начать.",
            new int[]{R, ZxKeys.NONE, Q, W, E, R, EN, D1}),
        new Game("Leovigildo.tap", "The Mojon Twins, 2014",
            "Часть 1 из 4. Если игра спросит управление — выберите Kempston. Помогите Леовигильдо добыть бумаги.",
            kempston(D2, D1, SP)),
        new Game("El Hobbit.tzx", "The Mojon Twins, 2013",
            "В меню 2 — Kempston. Огонь — говорить, осмотреть, надеть кольцо.",
            kempston(D2, D1, SP)),
        new Game("Mariano the Dragon.tzx", "The Mojon Twins, 2008",
            "Если игра спросит управление — выберите Kempston. Выполните задания и освободите друзей.",
            kempston(D2, D1, SP)),
    };

    static Game find(String file) {
        if (file == null) return null;
        for (Game g : LIST) if (g.file.equals(file)) return g;
        return null;
    }

    static int[] controls(String file) {
        Game g = find(file);
        return g == null ? null : g.controls;
    }

    private Games() {
    }
}
