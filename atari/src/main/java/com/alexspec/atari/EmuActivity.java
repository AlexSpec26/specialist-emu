package com.alexspec.atari;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

import com.alexspec.atari.core.AtariMachine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The Atari app's only screen: menu, programs, saves, speed, memory map, settings. Ported from
 * the ZX app's EmuActivity so the three emulators work alike; Atari specifics are the ROM/BASIC
 * menu and the file types. GPL-2.0-or-later, see atari/COPYING.
 */
public final class EmuActivity extends Activity implements MainView.Host {
    private static final int REQ_PROGRAM = 1;
    private static final int REQ_EXPORT_STATE = 3;
    private static final int REQ_IMPORT_STATE = 4;
    private static final int REQ_OS = 6;
    private static final int REQ_BASIC = 7;
    private static final int MAX_FILE = 32 * 1024 * 1024;
    /** programs, disks, cartridges (with CART header or raw: rom, bin; a52 = Atari 5200), tapes */
    private static final String[] TYPES = {"xex", "com", "exe", "atr", "xfd", "atx", "dcm", "car", "rom", "bin", "a52", "cas"};
    private static final String TYPES_TEXT = ".xex .com .exe .atr .xfd .atx .dcm .car .rom .bin .a52 .cas";

    private EmuThread emu;
    private AtariMachine machine;
    private MainView view;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Program currently running ("" = none / BASIC); also the control profile name. */
    private String program = "";
    private int pickSlot = -1;
    private final SparseIntArray physKeys = new SparseIntArray();
    private int openDialogs;
    private boolean tapPaused;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);

        machine = new AtariMachine();
        emu = new EmuThread(machine, () -> {
            MainView v = view;
            if (v != null) v.postInvalidateOnAnimation();
        });
        view = new MainView(this, emu, this);
        setContentView(view);
        applySettings();

        migrateOldLibrary();
        String saved = prefs.getString("autosaveProgram", null);
        File auto = autosaveFile();
        byte[] autoData = null;
        if (saved != null && auto.exists()) {
            try {
                autoData = readFile(auto);
            } catch (IOException e) {
                android.util.Log.w("Atari", "autosave", e);
            }
        }
        final String startProgram = autoData != null ? saved : "";
        final byte[] startState = autoData;
        setProgram(startProgram);
        emu.post(() -> {
            if (!openOnEmu(startProgram)) {
                // the program is gone: start plain BASIC, without its saved state
                openOnEmu("");
                ui.post(() -> setProgram(""));
                return;
            }
            if (startState != null && !machine.loadState(startState)) android.util.Log.w("Atari", "autosave rejected");
        });
        emu.start();
        if (!prefs.getBoolean("welcomed", false)) {
            prefs.edit().putBoolean("welcomed", true).apply();
            ui.post(this::showWelcome);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        emu.setPaused(false);
    }

    @Override
    protected void onPause() {
        view.releaseAll();
        machine.releaseAllKeys();
        final String prog = program;
        emu.post(() -> {
            byte[] st = machine.saveState();
            if (st == null) return;
            try {
                writeFile(autosaveFile(), st);
                prefs.edit().putString("autosaveProgram", prog).apply();
            } catch (IOException e) {
                android.util.Log.w("Atari", "autosave", e);
            }
        });
        emu.setPaused(true);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        emu.quit();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
        else {
            view.releaseAll();
            machine.releaseAllKeys();
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (view.isPicking()) {
            view.cancelPick();
            return;
        }
        if (view.isKeyboardVisible()) {
            view.setKeyboardVisible(false);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ files

    private File autosaveFile() {
        return new File(getFilesDir(), "autosave.sav");
    }

    /** Programs and saves live in Android/data/com.alexspec.atari/files (visible over USB). */
    private File dataRoot() {
        File ext = getExternalFilesDir(null);
        return ext != null ? ext : getFilesDir();
    }

    private File programsDir() {
        File d = new File(dataRoot(), "programs");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private File stateFile() {
        return stateFile(program);
    }

    /** Quick-save slot belonging to one program (by its file name); "" is plain BASIC. */
    private File stateFile(String prog) {
        File d = new File(dataRoot(), "states");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return new File(d, (prog.isEmpty() ? "_basic" : prog) + ".sav");
    }

    private String progTitle() {
        if (program.isEmpty()) return "Atari BASIC";
        Games.Game g = Games.find(program);
        return g != null ? g.title : program;
    }

    /** Folder for the core: the user's own ATARIXL.ROM / ATARIBAS.ROM live here. */
    private File systemDir() {
        return getFilesDir();
    }

    /** The file the machine runs for a program name ("" = BASIC, no file). */
    private String contentPath(String prog) throws IOException {
        if (prog.isEmpty()) return "";
        Games.Game g = Games.find(prog);
        if (g != null) {
            File d = new File(getFilesDir(), "demos");
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
            File f = new File(d, g.file);
            if (!f.exists()) writeFile(f, readAsset("demos/" + g.file));
            return f.getAbsolutePath();
        }
        File f = new File(programsDir(), prog);
        if (!f.exists()) throw new IOException("файл не найден: " + prog);
        return f.getAbsolutePath();
    }

    /** BASIC is on for BASIC itself, and for programs only if the user asked for it. */
    private boolean basicFor(String prog) {
        return prog.isEmpty() || prefs.getBoolean("gameBasic", false);
    }

    /** Emulation thread: starts the machine with a program; false (and a message) on failure. */
    private boolean openOnEmu(String prog) {
        try {
            if (machine.open(systemDir().getAbsolutePath(), contentPath(prog), basicFor(prog))) return true;
            ui.post(() -> toast("Не удалось запустить «" + (prog.isEmpty() ? "BASIC" : prog) + "»: ядро не узнало "
                    + "формат файла. Картридж без заголовка должен называться .rom/.bin (Atari 800/XL) или .a52 (5200)."));
        } catch (IOException e) {
            ui.post(() -> toast(e.getMessage()));
        }
        return false;
    }

    /**
     * Builds up to atari-build-10 kept imported programs as files/games/<sha256>.<ext> with their
     * names in the preferences "atari". Copy them once into programs/ under their names.
     */
    private void migrateOldLibrary() {
        if (prefs.getBoolean("migrated10", false)) return;
        prefs.edit().putBoolean("migrated10", true).apply();
        File[] old = new File(getFilesDir(), "games").listFiles(f -> !f.getName().endsWith(".tmp"));
        if (old == null) return;
        SharedPreferences names = getSharedPreferences("atari", MODE_PRIVATE);
        for (File f : old) {
            String name = names.getString("name:" + f.getName(), f.getName()).replaceAll("[/\\\\:*?\"<>|]", "_");
            File to = new File(programsDir(), name);
            if (to.exists()) continue;
            try {
                writeFile(to, readFile(f));
            } catch (IOException e) {
                android.util.Log.w("Atari", "migrate " + f, e);
            }
        }
    }

    private static String when(File f) {
        return new java.text.SimpleDateFormat("d MMM, HH:mm", new Locale("ru"))
                .format(new java.util.Date(f.lastModified()));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > MAX_FILE) throw new IOException("файл слишком большой");
        }
        return bos.toByteArray();
    }

    private static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    private byte[] readAsset(String name) throws IOException {
        try (InputStream in = getAssets().open(name)) {
            return readAll(in);
        }
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (!tmp.renameTo(f)) throw new IOException("не удалось сохранить " + f.getName());
    }

    private byte[] readUri(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("нет доступа к файлу");
            return readAll(in);
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (RuntimeException ignored) {
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "program.xex";
        return name.replaceAll("[/\\\\:*?\"<>|]", "_");
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ settings

    private void applySettings() {
        view.buttonCount = prefs.getInt("buttons", 3);
        view.joy8 = prefs.getBoolean("joy8", true);
        view.vibrate = prefs.getBoolean("vibrate", true);
        emu.sound = prefs.getBoolean("sound", true);
        int step = Math.max(0, Math.min(Speed.STEPS - 1, prefs.getInt("speedStep", Speed.REAL)));
        emu.speed = Speed.factor(step);
        view.speedBadge = Speed.badge(step);
        // memory visualisation
        boolean map = prefs.getBoolean("map", false);
        emu.post(() -> machine.setTrace(map));
        machine.trace.enabled = map;
        machine.trace.setColors(prefs.getInt("colR", 0xFF30FF40), prefs.getInt("colW2", 0xFFFF2A9E),
                prefs.getInt("colRW", 0xFFFFE030), prefs.getInt("colX", 0xFF20E8FF));
        machine.trace.setFlashFrames(prefs.getInt("flash", 5));
        view.mapOn = map;
        machine.trace.layout = Math.max(1, Math.min(4, prefs.getInt("mapW", 1)));
        view.ctrlAlpha = prefs.getInt("ctrlAlpha", 100) / 100f;
        view.config = ControlConfig.load(prefs, program);
        view.title = progTitle();
        view.map.basicOn = basicFor(program);
        machine.releaseAllKeys();
        view.configChanged();
    }

    private void setProgram(String name) {
        program = name == null ? "" : name;
        view.config = ControlConfig.load(prefs, program);
        view.title = progTitle() + (machine.is5200() ? " · Atari 5200" : "");
        view.map.basicOn = basicFor(program) && !machine.is5200();
        view.configChanged();
    }

    // ------------------------------------------------------------------ pause

    /** Shows a dialog; emulation stays paused while any dialog is open. */
    private AlertDialog show(AlertDialog.Builder b) {
        AlertDialog d = b.create();
        d.setOnDismissListener(x -> {
            openDialogs--;
            ui.post(this::updatePause); // a follow-up dialog may open from the click handler
        });
        openDialogs++;
        updatePause();
        d.show();
        return d;
    }

    private void updatePause() {
        boolean p = tapPaused || openDialogs > 0 || view.isPicking();
        if (p) {
            view.releaseAll();
            machine.releaseAllKeys();
        }
        emu.setUserPaused(p);
        if (!p && emu.stepping) emu.stepping = false;
        if (!p) emu.post(machine::forgetFuture); // play goes on: live controls from here
        view.paused = tapPaused || view.isPicking();
        view.invalidate();
    }

    @Override
    public void onScreenTapped() {
        tapPaused = !tapPaused;
        updatePause();
    }

    @Override
    public void onStep(int kind) {
        // single instruction: the map shows only that instruction; a second: the usual fading flashes
        emu.stepping = kind == 1 || kind == 2;
        emu.postKeepScreen(() -> {
            boolean ok = true;
            switch (kind) {
                case 0: ok = machine.backTime(AtariMachine.CPU_HZ); break;
                case 1: ok = machine.backInstructions(1); break;
                case 2: machine.stepInstruction(); break;
                default: machine.forward(AtariMachine.CPU_HZ); break;
            }
            if (!ok) {
                float h = machine.historySeconds();
                ui.post(() -> toast(String.format(Locale.US,
                        "Дальше назад нельзя: история хранит %.0f с (после загрузки — с её момента)", Math.max(0, h))));
            }
            ui.post(view::showMachineInfo);
        });
    }

    // ------------------------------------------------------------------ menu

    @Override
    public void onMenuRequested() {
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        items.add("🕹  Игры…");
        actions.add(this::showGames);
        items.add("📂  Открыть файл (.xex .atr .car .rom .a52 .cas)…");
        actions.add(this::pickProgram);
        items.add("📚  Мои программы…");
        actions.add(this::showLibrary);
        items.add("⌨️  Atari BASIC");
        actions.add(() -> runProgram(""));
        items.add("🎮  Настроить кнопки…");
        actions.add(this::showMapping);
        File st = stateFile();
        items.add("💾  Сохранить: " + progTitle());
        actions.add(this::quickSave);
        items.add(st.exists() ? "📥  Загрузить: " + progTitle() + " (" + when(st) + ")"
                : "📥  Загрузить: " + progTitle() + " — нет сохранения");
        actions.add(this::quickLoad);
        items.add("📤  Экспорт сохранения…");
        actions.add(this::exportState);
        items.add("📦  Импорт сохранения…");
        actions.add(this::importState);
        items.add("⏱  Скорость: " + Speed.describe(prefs.getInt("speedStep", Speed.REAL)) + "…");
        actions.add(this::showSpeed);
        items.add("🔬  Визуализация памяти…");
        actions.add(this::showVisualization);
        items.add("🔄  Сброс (выключить и включить)");
        actions.add(() -> {
            emu.post(() -> {
                machine.hardReset();
                machine.releaseAllKeys();
            });
            toast("Сброс: " + progTitle());
        });
        items.add("🧩  ПЗУ и BASIC…");
        actions.add(this::showRoms);
        items.add("⚙️  Настройки…");
        actions.add(this::showSettings);
        items.add("❓  Справка");
        actions.add(this::showHelp);
        items.add("ℹ️  О программе и лицензиях");
        actions.add(this::showAbout);

        show(new AlertDialog.Builder(this)
                .setTitle("Atari 65XE")
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run()));
    }

    private void showWelcome() {
        show(new AlertDialog.Builder(this)
                .setTitle("Atari 65XE")
                .setMessage("Включён Atari BASIC (свободная замена Altirra). Клавиатура — кнопка ⌨ "
                        + "(SHIFT и CTRL нажимаются один раз и действуют на следующую клавишу).\n\n"
                        + "Своя программа, диск или картридж — меню ☰ → «Открыть файл». В «Играх» — "
                        + "маленькая демо-игра.\n\n"
                        + "Тап по экрану — пауза. Как в «Специалисте» и ZX: скорость, шаги по командам, "
                        + "карта памяти и экскурсия по ней.")
                .setPositiveButton("К играм", (d, w) -> showGames())
                .setNegativeButton("Позже", null));
    }

    private void showSpeed() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        android.widget.TextView label = new android.widget.TextView(this);
        label.setTextSize(18);
        label.setGravity(android.view.Gravity.CENTER);
        android.widget.TextView hint = new android.widget.TextView(this);
        hint.setText("Каждый шаг — вдвое. Вправо — медленнее, до 1 такта в секунду: видно, как луч рисует "
                + "кадр и как работает каждая команда. В паузе — шаги по одной команде и по секунде.");
        android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(Speed.STEPS - 1);
        int cur = prefs.getInt("speedStep", Speed.REAL);
        bar.setProgress(cur);
        Runnable upd = () -> label.setText(Speed.describe(bar.getProgress()));
        upd.run();
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {
                upd.run();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
            }
        });
        box.addView(label);
        box.addView(bar);
        box.addView(hint);
        show(new AlertDialog.Builder(this)
                .setTitle("Скорость")
                .setView(box)
                .setPositiveButton("OK", (dlg, w) -> {
                    prefs.edit().putInt("speedStep", bar.getProgress()).apply();
                    applySettings();
                })
                .setNeutralButton("Реальная", (dlg, w) -> {
                    prefs.edit().putInt("speedStep", Speed.REAL).apply();
                    applySettings();
                })
                .setNegativeButton("Отмена", null));
    }

    private static final String[] COLOR_NAMES = {"зелёный", "красный", "малиновый", "жёлтый", "голубой", "синий",
            "пурпурный", "оранжевый", "белый"};
    private static final int[] COLOR_VALUES = {0xFF30FF40, 0xFFFF3030, 0xFFFF2A9E, 0xFFFFE030, 0xFF20E8FF, 0xFF4070FF,
            0xFFFF40FF, 0xFFFF9020, 0xFFFFFFFF};

    private static String colorName(int c) {
        for (int i = 0; i < COLOR_VALUES.length; i++) if (COLOR_VALUES[i] == c) return COLOR_NAMES[i];
        return String.format("#%06X", c & 0xFFFFFF);
    }

    // ------------------------------------------------------------------ ROM and BASIC

    private void showRoms() {
        boolean gameBasic = prefs.getBoolean("gameBasic", false);
        boolean ownOs = new File(systemDir(), "ATARIXL.ROM").exists();
        boolean ownBasic = new File(systemDir(), "ATARIBAS.ROM").exists();
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        items.add("BASIC при запуске программ: " + (gameBasic ? "ВКЛ" : "выкл (нужно большинству игр)"));
        acts.add(() -> {
            prefs.edit().putBoolean("gameBasic", !gameBasic).apply();
            if (!program.isEmpty()) {
                runProgram(program);
                toast("Программа запущена заново: BASIC " + (!gameBasic ? "включён" : "выключен"));
            }
            showRoms();
        });
        items.add("ОС: " + (ownOs ? "своё ПЗУ (ATARIXL.ROM)" : "Altirra OS (свободная замена)"));
        acts.add(() -> pickRom(REQ_OS));
        items.add("BASIC: " + (ownBasic ? "своё ПЗУ (ATARIBAS.ROM)" : "Altirra BASIC (свободная замена)"));
        acts.add(() -> pickRom(REQ_BASIC));
        if (ownOs || ownBasic) {
            items.add("↺  Вернуть свободные Altirra OS и BASIC");
            acts.add(() -> {
                //noinspection ResultOfMethodCallIgnored
                new File(systemDir(), "ATARIXL.ROM").delete();
                //noinspection ResultOfMethodCallIgnored
                new File(systemDir(), "ATARIBAS.ROM").delete();
                runProgram(program);
                toast("Снова Altirra OS и BASIC");
            });
        }
        show(new AlertDialog.Builder(this)
                .setTitle("ПЗУ и BASIC")
                .setItems(items.toArray(new String[0]), (d, which) -> acts.get(which).run())
                .setPositiveButton("Готово", null));
    }

    private void pickRom(int request) {
        show(new AlertDialog.Builder(this)
                .setTitle(request == REQ_OS ? "Своё ПЗУ ОС" : "Своё ПЗУ BASIC")
                .setMessage(request == REQ_OS
                        ? "Файл ОС 800XL/65XE — ровно 16 КБ (16384 байт). Оригинальное ПЗУ Atari нужно "
                                + "взять со своей машины; с ним совместимы игры, которым не подходит Altirra."
                        : "Файл BASIC — ровно 8 КБ (8192 байт), например Atari BASIC rev. C со своей машины.")
                .setPositiveButton("Выбрать файл…", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(i, request);
                })
                .setNegativeButton("Отмена", null));
    }

    private void onRomChosen(Uri uri, boolean os) {
        int size = os ? 16384 : 8192;
        try {
            byte[] data = readUri(uri);
            if (data.length != size) {
                toast("Нужен файл ровно " + size + " байт, а этот — " + data.length);
                return;
            }
            writeFile(new File(systemDir(), os ? "ATARIXL.ROM" : "ATARIBAS.ROM"), data);
            runProgram(program);
            toast((os ? "ОС" : "BASIC") + ": своё ПЗУ установлено, машина перезапущена");
        } catch (IOException e) {
            toast("Ошибка: " + e.getMessage());
        }
    }

    private void showVisualization() {
        boolean on = prefs.getBoolean("map", false);
        int perLine = prefs.getInt("mapW", 1);
        int flash = prefs.getInt("flash", 5);
        String[] keys = {"colR", "colW2", "colRW", "colX"};
        int[] defs = {0xFF30FF40, 0xFFFF2A9E, 0xFFFFE030, 0xFF20E8FF};
        String[] what = {"Чтение", "Запись", "Чтение и запись", "Исполнение (команды и операнды)"};
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        items.add("Карта памяти: " + (on ? "ВКЛ" : "выкл"));
        acts.add(() -> {
            boolean nv = !on;
            prefs.edit().putBoolean("map", nv).apply();
            if (nv && !prefs.contains("ctrlAlpha")) prefs.edit().putInt("ctrlAlpha", 50).apply();
            applySettings();
            if (nv && view.getWidth() > view.getHeight())
                toast("Карта памяти показывается в портретной ориентации");
            if (nv && !prefs.getBoolean("tourOffered", false)) {
                prefs.edit().putBoolean("tourOffered", true).apply();
                show(new AlertDialog.Builder(this)
                        .setTitle("Экскурсия по памяти")
                        .setMessage("Показать, как устроена память Atari? Каждая область карты будет "
                                + "подсвечена и объяснена: нулевая страница, стек, системные переменные, BASIC, "
                                + "список отображения ANTIC, экран, шрифт, регистры микросхем, ПЗУ.\n\n"
                                + "Экскурсию можно пройти и позже: меню → «Визуализация памяти».")
                        .setPositiveButton("Показать", (d, w) -> startTour())
                        .setNegativeButton("Позже", null));
            } else {
                showVisualization();
            }
        });
        items.add("Байт в строке карты: " + perLine + (perLine == 1 ? " (как у «Специалиста»)" : ""));
        acts.add(() -> {
            String[] names = {"1 байт — шрифты, тайлы 8×8, игроки", "2 байта — картинки 16 точек", "3 байта — картинки 24 точки",
                "4 байта — картинки 32 точки"};
            show(new AlertDialog.Builder(this)
                    .setTitle("Байт в строке карты")
                    .setSingleChoiceItems(names, perLine - 1, (d2, w) -> {
                        prefs.edit().putInt("mapW", w + 1).apply();
                        d2.dismiss();
                        applySettings();
                        showVisualization();
                    }));
        });
        items.add("🎓  Экскурсия по памяти");
        acts.add(this::startTour);
        for (int i = 0; i < 4; i++) {
            final int k = i;
            items.add(what[k] + ": " + colorName(prefs.getInt(keys[k], defs[k])));
            acts.add(() -> {
                int cur = prefs.getInt(keys[k], defs[k]);
                int sel = -1;
                for (int j = 0; j < COLOR_VALUES.length; j++) if (COLOR_VALUES[j] == cur) sel = j;
                show(new AlertDialog.Builder(this)
                        .setTitle(what[k])
                        .setSingleChoiceItems(COLOR_NAMES, sel, (d2, w) -> {
                            prefs.edit().putInt(keys[k], COLOR_VALUES[w]).apply();
                            d2.dismiss();
                            applySettings();
                            showVisualization();
                        }));
            });
        }
        items.add(String.format(Locale.US, "Вспышка: %.1f с", flash / 50f));
        acts.add(() -> {
            String[] names = {"0.1 с", "0.2 с", "0.5 с", "1 с"};
            int[] vals = {5, 10, 25, 50};
            int sel = 0;
            for (int i = 0; i < vals.length; i++) if (vals[i] == flash) sel = i;
            show(new AlertDialog.Builder(this)
                    .setTitle("Длительность вспышки")
                    .setSingleChoiceItems(names, sel, (d2, w) -> {
                        prefs.edit().putInt("flash", vals[w]).apply();
                        d2.dismiss();
                        applySettings();
                        showVisualization();
                    }));
        });
        items.add("Прозрачность джойстика и кнопок: " + (100 - prefs.getInt("ctrlAlpha", 100)) + "%");
        acts.add(this::showAlpha);
        items.add("Стереть следы обращений");
        acts.add(() -> {
            emu.post(machine::clearTrace);
            toast("Следы стёрты");
        });
        show(new AlertDialog.Builder(this)
                .setTitle("Визуализация памяти")
                .setItems(items.toArray(new String[0]), (d, which) -> acts.get(which).run())
                .setPositiveButton("Готово", null));
    }

    private void startTour() {
        if (!prefs.getBoolean("map", false)) {
            prefs.edit().putBoolean("map", true).apply();
            if (!prefs.contains("ctrlAlpha")) prefs.edit().putInt("ctrlAlpha", 50).apply();
            applySettings();
        }
        prefs.edit().putBoolean("tourOffered", true).apply();
        if (view.getWidth() > view.getHeight()) {
            toast("Поверните телефон вертикально — карта видна в портретной ориентации");
            return;
        }
        view.post(view::startTutorial);
    }

    private void showAlpha() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        android.widget.TextView label = new android.widget.TextView(this);
        label.setTextSize(22);
        label.setGravity(android.view.Gravity.CENTER);
        android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(90); // opacity 10..100 %
        bar.setProgress(prefs.getInt("ctrlAlpha", 100) - 10);
        Runnable apply = () -> {
            int a = 10 + bar.getProgress();
            label.setText("Прозрачность: " + (100 - a) + "%");
            prefs.edit().putInt("ctrlAlpha", a).apply();
            view.ctrlAlpha = a / 100f;
            view.invalidate();
        };
        apply.run();
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {
                apply.run();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
            }
        });
        box.addView(label);
        box.addView(bar);
        show(new AlertDialog.Builder(this)
                .setTitle("Джойстик и кнопки")
                .setView(box)
                .setPositiveButton("Готово", (d, w) -> showVisualization()));
    }

    @Override
    public void onKeyboardToggled(boolean visible) {
        machine.releaseAllKeys();
    }

    private void showHelp() {
        show(new AlertDialog.Builder(this)
                .setTitle("Справка")
                .setMessage("Программы: «Открыть файл» — .xex .com .exe (программы), .atr .xfd .atx .dcm (диски), "
                        + ".car, .rom, .bin (картриджи), .a52 (картриджи приставки 5200: эмулятор сам включит 5200, "
                        + "START/SELECT/OPTION — её START/PAUSE/RESET, цифры — её клавиатура), .cas (кассеты). "
                        + "Игры для Atari 2600 (.a26) — другая машина, не запускаются. Файл сохраняется в «Мои программы». Многим играм "
                        + "после загрузки нужно нажать START.\n\n"
                        + "Джойстик и кнопки: по умолчанию — джойстик Atari и FIRE, START, пробел, RETURN. "
                        + "«Настроить кнопки» — назначить любую клавишу или готовую раскладку; для каждой программы "
                        + "раскладка своя.\n\n"
                        + "Клавиатура: кнопка ⌨. SHIFT и CTRL нажимаются один раз и действуют на следующую клавишу "
                        + "(повторное нажатие — отмена). Красным на клавише — символ с SHIFT, синим — с CTRL "
                        + "(CTRL + − = + * — стрелки курсора). Λ — инверсия, CAPS — регистр букв. В верхнем ряду — "
                        + "консольные клавиши HELP, START, SELECT, OPTION, RESET и BREAK.\n\n"
                        + "Пауза: нажмите на экран — посреди экрана появится маленький знак паузы. В паузе вместо названия программы (сбоку от экрана, если телефон лежит) — кнопки: на секунду и на одну команду "
                        + "назад и вперёд. После шага 4 секунды видны луч и текущая команда процессора с регистрами (под панелью, вне картинки). "
                        + "Назад можно уйти на минуту; вперёд после этого повторяется то, что было, пока "
                        + "не продолжите игру. При шагах по одной команде на карте памяти светится только эта "
                        + "команда. Пока открыто меню, эмуляция тоже стоит.\n\n"
                        + "Сохранения: у каждой программы своё. Значок 💾 в списках — у программы есть сохранение.\n\n"
                        + "Скорость: от ×4 до 1 такта в секунду, каждый шаг ползунка — вдвое.\n\n"
                        + "Визуализация памяти: карта всех 64 КБ под экраном, байты вспыхивают при чтении, записи "
                        + "и исполнении процессором; тап по карте — что это за адрес; есть экскурсия.\n\n"
                        + "ПЗУ и BASIC: встроены свободные Altirra OS и BASIC; можно поставить свои оригинальные.\n\n"
                        + "Файлы: Android/data/com.alexspec.atari/files (папки programs и states), их видно "
                        + "с компьютера по USB.\n\n"
                        + "Физическая клавиатура: буквы, цифры, Enter, Backspace, Esc, Tab, Shift, Ctrl; "
                        + "F1/F2/F3 — START/SELECT/OPTION, F4 — HELP. Геймпад: крестовина — джойстик, "
                        + "A/B/X/Y — кнопки 1–4.")
                .setPositiveButton("OK", null));
    }

    private void showAbout() {
        show(new AlertDialog.Builder(this)
                .setTitle("О программе и лицензиях")
                .setMessage("Эмулятор Atari 800XL / 65XE (PAL, 64 КБ): процессор 6502, видео ANTIC и GTIA, звук "
                        + "POKEY, файлы XEX, ATR, CAR, CAS. Инструменты — как в эмуляторах «Специалиста» и ZX "
                        + "из той же мастерской: карта памяти, скорость, шаги по командам.\n\n"
                        + "Ядро эмуляции: Atari800 7.0.0 (libretro), GPL-2.0-or-later; приложение распространяется "
                        + "под той же лицензией. Свободные замены Atari OS и BASIC — Altirra © Avery Lee.\n\n"
                        + "«Поймай звезду» — демо-игра, написанная для этого приложения, CC0.\n\n"
                        + "Исходники: github.com/AlexSpec26/specialist-emu, папка atari; архив исходников "
                        + "приложен к каждому релизу.")
                .setPositiveButton("Лицензии", (d, w) -> showLicenses())
                .setNegativeButton("OK", null));
    }

    private void showLicenses() {
        try {
            String text = new String(readAsset("licenses.txt"), java.nio.charset.StandardCharsets.UTF_8);
            show(new AlertDialog.Builder(this).setTitle("Licenses").setMessage(text).setPositiveButton("OK", null));
        } catch (IOException e) {
            toast(e.getMessage());
        }
    }

    private void showSettings() {
        String[] items = {
            "Кнопок справа: " + view.buttonCount,
            "Джойстик: " + (view.joy8 ? "8 направлений" : "4 направления"),
            "Звук: " + (emu.sound ? "вкл" : "выкл"),
            "Вибрация: " + (view.vibrate ? "вкл" : "выкл"),
        };
        show(new AlertDialog.Builder(this)
                .setTitle("Настройки")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: {
                            String[] counts = {"1", "2", "3", "4"};
                            show(new AlertDialog.Builder(this)
                                    .setTitle("Кнопок справа")
                                    .setSingleChoiceItems(counts, view.buttonCount - 1, (d2, w) -> {
                                        prefs.edit().putInt("buttons", w + 1).apply();
                                        d2.dismiss();
                                        applySettings();
                                        showSettings();
                                    }));
                            return;
                        }
                        case 1:
                            prefs.edit().putBoolean("joy8", !view.joy8).apply();
                            break;
                        case 2:
                            prefs.edit().putBoolean("sound", !emu.sound).apply();
                            break;
                        default:
                            prefs.edit().putBoolean("vibrate", !view.vibrate).apply();
                            break;
                    }
                    applySettings();
                    showSettings();
                })
                .setPositiveButton("Готово", null));
    }

    // ------------------------------------------------------------------ key mapping

    private void showMapping() {
        int slots = ControlConfig.BUTTON0 + Math.min(view.buttonCount, ControlConfig.MAX_BUTTONS);
        String[] items = new String[slots + 2];
        for (int i = 0; i < slots; i++) {
            int code = view.config.keys[i];
            items[i] = ControlConfig.SLOT_NAMES[i] + "   →   " + AtariKeys.fullName(code)
                    + (view.config.ctrlArrows && i < ControlConfig.BUTTON0 ? " + CTRL" : "");
        }
        items[slots] = "⚡  Готовая раскладка…";
        items[slots + 1] = "↺  Сбросить к стандартным";
        String who = program.isEmpty() ? "общие" : progTitle();
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Кнопки: " + who)
                .setItems(items, (d, which) -> {
                    if (which == slots) {
                        show(new AlertDialog.Builder(this)
                                .setTitle("Готовая раскладка")
                                .setItems(ControlConfig.PRESET_NAMES, (d2, w) -> {
                                    view.config.set(w);
                                    view.config.save(prefs, program);
                                    machine.releaseAllKeys();
                                    view.configChanged();
                                    showMapping();
                                }));
                        return;
                    }
                    if (which == slots + 1) {
                        ControlConfig.remove(prefs, program);
                        if (program.isEmpty()) {
                            view.config.setDefaults();
                            view.config.save(prefs, program);
                        } else {
                            view.config = ControlConfig.load(prefs, program);
                        }
                        machine.releaseAllKeys();
                        view.configChanged();
                        showMapping();
                        return;
                    }
                    pickKindFor(which);
                })
                .setPositiveButton("Готово", null);
        if (!program.isEmpty()) {
            b.setNeutralButton("Сделать общими", (d, w) -> {
                view.config.save(prefs, ControlConfig.DEFAULT_PROFILE);
                toast("Эта раскладка теперь используется по умолчанию");
            });
        }
        show(b);
    }

    /** a control slot: either a joystick direction or a key picked on the keyboard */
    private void pickKindFor(int slot) {
        String[] kinds = {"Клавиша на клавиатуре…", "Джойстик ↑", "Джойстик ↓", "Джойстик ←", "Джойстик →",
            "Огонь (FIRE)", "Ничего"};
        int[] codes = {0, AtariMachine.JOY_UP, AtariMachine.JOY_DOWN, AtariMachine.JOY_LEFT,
            AtariMachine.JOY_RIGHT, AtariMachine.FIRE, AtariKeys.NONE};
        show(new AlertDialog.Builder(this)
                .setTitle(ControlConfig.SLOT_NAMES[slot])
                .setItems(kinds, (d, w) -> {
                    if (w == 0) {
                        pickSlot = slot;
                        view.startKeyPick("Нажмите клавишу для «" + ControlConfig.SLOT_NAMES[slot] + "»");
                        updatePause();
                        return;
                    }
                    view.config.keys[slot] = codes[w];
                    view.config.save(prefs, program);
                    machine.releaseAllKeys();
                    view.configChanged();
                    showMapping();
                }));
    }

    @Override
    public void onKeyPicked(int code) {
        if (pickSlot >= 0) {
            view.config.keys[pickSlot] = code;
            view.config.save(prefs, program);
            pickSlot = -1;
            machine.releaseAllKeys();
            view.configChanged();
            updatePause();
            showMapping();
        }
    }

    @Override
    public void onPickCancelled() {
        pickSlot = -1;
        updatePause();
        showMapping();
    }

    // ------------------------------------------------------------------ games and programs

    private void showGames() {
        String[] shown = new String[Games.LIST.length];
        for (int i = 0; i < shown.length; i++) {
            Games.Game g = Games.LIST[i];
            shown[i] = (stateFile(g.file).exists() ? "💾 " : "      ") + g.title;
        }
        show(new AlertDialog.Builder(this)
                .setTitle("Игры")
                .setItems(shown, (d, which) -> showGame(Games.LIST[which]))
                .setNeutralButton("Лицензии", (d, w) -> showAbout())
                .setNegativeButton("Закрыть", null));
    }

    private void showGame(Games.Game g) {
        File st = stateFile(g.file);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(g.title)
                .setMessage(g.author + "\n\n" + g.hint + "\n\nКнопки на экране уже настроены для этой игры.")
                .setPositiveButton(st.exists() ? "Заново" : "Играть", (d, w) -> runProgram(g.file))
                .setNegativeButton("Назад", (d, w) -> showGames());
        if (st.exists()) b.setNeutralButton("Продолжить", (d, w) -> resumeFrom(g.file, st));
        show(b);
    }

    private void pickProgram() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_PROGRAM);
    }

    /** Starts a program (file name in the library or a bundled game; "" = BASIC) from power-on. */
    private void runProgram(String name) {
        emu.post(() -> {
            if (!openOnEmu(name)) return;
            machine.releaseAllKeys();
            ui.post(() -> {
                setProgram(name);
                toast("Запущено: " + progTitle());
            });
        });
    }

    private void resumeFrom(String name, File st) {
        try {
            byte[] data = readFile(st);
            emu.post(() -> {
                if (!openOnEmu(name)) return;
                if (!machine.loadState(data)) {
                    ui.post(() -> toast("Сохранение не подходит (другая версия или программа)"));
                    return;
                }
                ui.post(() -> {
                    setProgram(name);
                    toast("Продолжаем: " + progTitle());
                });
            });
        } catch (IOException e) {
            toast(e.getMessage());
        }
    }

    private void showLibrary() {
        File[] files = programsDir().listFiles(f -> !f.getName().endsWith(".tmp"));
        if (files == null || files.length == 0) {
            show(new AlertDialog.Builder(this)
                    .setTitle("Мои программы")
                    .setMessage("Пока пусто. Откройте файл .xex, .atr, .car или .cas через меню — он появится здесь.")
                    .setPositiveButton("Открыть…", (d, w) -> pickProgram())
                    .setNegativeButton("Закрыть", null));
            return;
        }
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        String[] names = new String[files.length];
        String[] shown = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            names[i] = files[i].getName();
            shown[i] = (stateFile(names[i]).exists() ? "💾 " : "      ") + names[i];
        }
        show(new AlertDialog.Builder(this)
                .setTitle("Мои программы")
                .setItems(shown, (d, which) -> launchFromLibrary(names[which], files[which]))
                .setNeutralButton("Удалить…", (d, w) -> deletePrograms(files, names))
                .setNegativeButton("Закрыть", null));
    }

    private void launchFromLibrary(String name, File file) {
        File st = stateFile(name);
        if (!st.exists()) {
            runProgram(name);
            return;
        }
        show(new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(new String[]{"▶  Продолжить с сохранения (" + when(st) + ")", "⟲  Начать заново"}, (d, w) -> {
                    if (w == 0) resumeFrom(name, st);
                    else runProgram(name);
                }));
    }

    private void deletePrograms(File[] files, String[] names) {
        boolean[] checked = new boolean[files.length];
        show(new AlertDialog.Builder(this)
                .setTitle("Удалить программы")
                .setMultiChoiceItems(names, checked, (d, w, c) -> checked[w] = c)
                .setPositiveButton("Удалить", (d, w) -> {
                    int n = 0;
                    for (int i = 0; i < files.length; i++) {
                        if (checked[i] && files[i].delete()) {
                            ControlConfig.remove(prefs, names[i]);
                            //noinspection ResultOfMethodCallIgnored
                            stateFile(names[i]).delete();
                            n++;
                        }
                    }
                    toast("Удалено: " + n);
                })
                .setNegativeButton("Отмена", null));
    }

    private void quickSave() {
        final File f = stateFile();
        final String name = progTitle();
        emu.post(() -> {
            byte[] st = machine.saveState();
            if (st == null) {
                ui.post(() -> toast("Машина не запущена"));
                return;
            }
            try {
                writeFile(f, st);
                ui.post(() -> toast("Сохранено: " + name));
            } catch (IOException e) {
                ui.post(() -> toast("Ошибка: " + e.getMessage()));
            }
        });
    }

    private void quickLoad() {
        File f = stateFile();
        if (!f.exists()) {
            toast("Для «" + progTitle() + "» сохранения пока нет");
            return;
        }
        resumeFrom(program, f);
    }

    private void exportState() {
        File f = stateFile();
        if (!f.exists()) {
            toast("Для «" + progTitle() + "» сохранения пока нет — сначала «Сохранить»");
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, f.getName());
        startActivityForResult(i, REQ_EXPORT_STATE);
    }

    private void importState() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT_STATE);
    }

    private void onExportChosen(Uri uri) {
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("нет доступа к файлу");
            out.write(readFile(stateFile()));
            toast("Сохранение «" + progTitle() + "» экспортировано");
        } catch (IOException e) {
            toast("Ошибка: " + e.getMessage());
        }
    }

    private void onImportChosen(Uri uri) {
        final byte[] data;
        try {
            data = readUri(uri);
        } catch (IOException e) {
            toast("Ошибка: " + e.getMessage());
            return;
        }
        String fname = displayName(uri);
        String base = fname.toLowerCase(Locale.ROOT).endsWith(".sav") ? fname.substring(0, fname.length() - 4) : fname;
        final String prog = "_basic".equals(base) ? "" : base;
        emu.post(() -> {
            if (!openOnEmu(prog)) return;
            if (!machine.loadState(data)) { // validates the file and restores the machine
                ui.post(() -> toast(fname + ": это не сохранение эмулятора Atari (или для другой программы)"));
                return;
            }
            ui.post(() -> {
                setProgram(prog);
                try {
                    writeFile(stateFile(prog), data);
                } catch (IOException e) {
                    android.util.Log.w("Atari", "import copy", e);
                }
                toast("Импортировано: " + progTitle());
            });
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT_STATE) {
            onExportChosen(uri);
        } else if (requestCode == REQ_IMPORT_STATE) {
            onImportChosen(uri);
        } else if (requestCode == REQ_OS || requestCode == REQ_BASIC) {
            onRomChosen(uri, requestCode == REQ_OS);
        } else if (requestCode == REQ_PROGRAM) {
            try {
                String name = displayName(uri);
                String e = ext(name);
                if (e.equals("a26")) {
                    toast(name + ": это игра для Atari 2600 (VCS) — другой машины; этот эмулятор её не запускает");
                    return;
                }
                if (!Arrays.asList(TYPES).contains(e)) {
                    toast(name + ": нужен файл " + TYPES_TEXT);
                    return;
                }
                byte[] bytes = readUri(uri);
                if ((e.equals("rom") || e.equals("bin")) && AtariMachine.looksLike2600(bytes)) {
                    show(new AlertDialog.Builder(this)
                            .setTitle(name)
                            .setMessage("Похоже, это картридж Atari 2600 (VCS): " + bytes.length + " байт, адреса "
                                    + "переходов — как у 2600. Это другая приставка, этот эмулятор её не запускает.\n\n"
                                    + "Для Atari 800/XL/XE ищите версию игры в формате .xex, .atr, .car или .rom "
                                    + "(обычно 8 или 16 КБ), для приставки 5200 — .a52.")
                            .setPositiveButton("Всё равно открыть", (d, w) -> importProgram(name, bytes))
                            .setNegativeButton("Отмена", null));
                    return;
                }
                importProgram(name, bytes);
            } catch (IOException e) {
                toast("Ошибка: " + e.getMessage());
            }
        }
    }

    private void importProgram(String name, byte[] data) {
        try {
            writeFile(new File(programsDir(), name), data);
            runProgram(name);
        } catch (IOException e) {
            toast("Ошибка: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ physical keyboard / gamepad

    private int slotForKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return ControlConfig.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return ControlConfig.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return ControlConfig.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return ControlConfig.RIGHT;
            case KeyEvent.KEYCODE_BUTTON_A: return ControlConfig.BUTTON0;
            case KeyEvent.KEYCODE_BUTTON_B: return ControlConfig.BUTTON0 + 1;
            case KeyEvent.KEYCODE_BUTTON_X: return ControlConfig.BUTTON0 + 2;
            case KeyEvent.KEYCODE_BUTTON_Y: return ControlConfig.BUTTON0 + 3;
            default: return -1;
        }
    }

    /** Atari keys for a host key (with SHIFT for shifted punctuation). */
    private static int[] atariForKey(KeyEvent ev) {
        int k = ev.getKeyCode();
        if (k >= KeyEvent.KEYCODE_A && k <= KeyEvent.KEYCODE_Z)
            return new int[]{AtariKeys.byLabel(String.valueOf((char) ('A' + k - KeyEvent.KEYCODE_A)))};
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9 && !ev.isShiftPressed())
            return new int[]{AtariKeys.byLabel(String.valueOf((char) ('0' + k - KeyEvent.KEYCODE_0)))};
        switch (k) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return new int[]{AtariMachine.RETURN};
            case KeyEvent.KEYCODE_SPACE: return new int[]{AtariMachine.SPACE};
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return new int[]{AtariMachine.SHIFT};
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT: return new int[]{AtariMachine.CTRL};
            case KeyEvent.KEYCODE_DEL: return new int[]{AtariMachine.BACKSPACE};
            case KeyEvent.KEYCODE_ESCAPE: return new int[]{AtariMachine.ESCAPE};
            case KeyEvent.KEYCODE_TAB: return new int[]{AtariMachine.TAB};
            case KeyEvent.KEYCODE_CAPS_LOCK: return new int[]{AtariMachine.CAPS};
            case KeyEvent.KEYCODE_F1:
            case KeyEvent.KEYCODE_BUTTON_START: return new int[]{AtariMachine.START};
            case KeyEvent.KEYCODE_F2: return new int[]{AtariMachine.SELECT};
            case KeyEvent.KEYCODE_F3: return new int[]{AtariMachine.OPTION};
            case KeyEvent.KEYCODE_F4: return new int[]{AtariMachine.HELP};
            case KeyEvent.KEYCODE_BREAK: return new int[]{AtariMachine.BREAK};
            default: break;
        }
        // punctuation by the character it types: the Atari key, with SHIFT if needed
        int ch = ev.getUnicodeChar();
        String plain = ",./;-=+*<>", shifted = "!\"#$%&'@()[]?:_|\\^";
        String[] shiftedKeys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0", ",", ".", "/", ";", "-", "=", "+", "*"};
        int pi = plain.indexOf(ch);
        if (pi >= 0) return new int[]{AtariKeys.byLabel(String.valueOf((char) ch))};
        int si = shifted.indexOf(ch);
        if (si >= 0) return new int[]{AtariMachine.SHIFT, AtariKeys.byLabel(shiftedKeys[si])};
        return null;
    }

    private final android.util.SparseArray<int[]> physAtari = new android.util.SparseArray<>();

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent ev) {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT || keyCode == KeyEvent.KEYCODE_MENU) {
            if (ev.getRepeatCount() == 0) onMenuRequested();
            return true;
        }
        int slot = slotForKey(keyCode);
        int[] keys = slot >= 0 ? null : atariForKey(ev);
        if (keys != null) for (int c : keys) if (c == AtariKeys.NONE) keys = null;
        if (slot < 0 && keys == null) return super.onKeyDown(keyCode, ev);
        if (ev.getRepeatCount() > 0 || physKeys.indexOfKey(keyCode) >= 0 || physAtari.indexOfKey(keyCode) >= 0) return true;
        if (slot >= 0) {
            physKeys.put(keyCode, slot);
            view.slotKey(slot, true);
        } else {
            physAtari.put(keyCode, keys);
            for (int c : keys) machine.keyDown(c);
        }
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent ev) {
        int idx = physKeys.indexOfKey(keyCode);
        if (idx >= 0) {
            view.slotKey(physKeys.valueAt(idx), false);
            physKeys.removeAt(idx);
            return true;
        }
        int zi = physAtari.indexOfKey(keyCode);
        if (zi >= 0) {
            for (int c : physAtari.valueAt(zi)) machine.keyUp(c);
            physAtari.removeAt(zi);
            return true;
        }
        return super.onKeyUp(keyCode, ev);
    }

    // ------------------------------------------------------------------ misc

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
