package com.alexspec.zx;

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

import com.alexspec.zx.core.MemTrace;
import com.alexspec.zx.core.Spectrum48;
import com.alexspec.zx.core.Tape;

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

public final class EmuActivity extends Activity implements MainView.Host {
    private static final int REQ_PROGRAM = 1;
    private static final int REQ_EXPORT_STATE = 3;
    private static final int REQ_IMPORT_STATE = 4;
    private static final int REQ_MIC = 5;
    private static final int MAX_FILE = 4 * 1024 * 1024;

    private EmuThread emu;
    private Spectrum48 machine;
    private MainView view;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Program currently running ("" = none / BASIC); also the control profile name. */
    private String program = "";
    private int pickSlot = -1;
    private final SparseIntArray physKeys = new SparseIntArray();
    private int openDialogs;
    private final MicTape mic = new MicTape();
    private boolean resumed;
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

        byte[] rom;
        try {
            rom = readAsset("48k.rom");
        } catch (IOException e) {
            throw new IllegalStateException("48k.rom missing", e);
        }
        machine = new Spectrum48(rom);
        emu = new EmuThread(machine, () -> {
            MainView v = view;
            if (v != null) v.postInvalidateOnAnimation();
        });
        view = new MainView(this, emu, this);
        setContentView(view);
        applySettings();

        String saved = prefs.getString("autosaveProgram", null);
        File auto = autosaveFile();
        if (saved != null && auto.exists()) {
            try {
                final byte[] autoData = readFile(auto);
                emu.post(() -> {
                    try {
                        machine.loadState(autoData);
                    } catch (IOException e) {
                        android.util.Log.w("ZX", "autosave", e);
                    }
                });
                setProgram(saved);
            } catch (IOException e) {
                android.util.Log.w("ZX", "autosave", e);
            }
        }
        emu.start();
        if (!prefs.getBoolean("welcomed", false)) {
            prefs.edit().putBoolean("welcomed", true).apply();
            ui.post(this::showWelcome);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        hideSystemBars();
        emu.setPaused(false);
        updateMic();
    }

    @Override
    protected void onPause() {
        resumed = false;
        updateMic();
        view.releaseAll();
        machine.releaseAllKeys();
        final String prog = program;
        emu.post(() -> {
            try {
                writeFile(autosaveFile(), machine.saveState());
                prefs.edit().putString("autosaveProgram", prog).apply();
            } catch (IOException e) {
                android.util.Log.w("ZX", "autosave", e);
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

    /** Programs and saves live in Android/data/com.alexspec.zx/files (visible over USB). */
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
        if (program.isEmpty()) return "BASIC";
        Games.Game g = Games.find(program);
        return g != null ? g.title() : program;
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
        if (name == null) name = "program.tap";
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
        // black-and-white TV: the machine draws in greys; post() also redraws a paused picture
        boolean mono = prefs.getBoolean("mono", false);
        if (mono != machine.isMono()) emu.post(() -> machine.setMono(mono));
        emu.sound = prefs.getBoolean("sound", true);
        int step = Math.max(0, Math.min(Speed.STEPS - 1, prefs.getInt("speedStep", Speed.REAL)));
        // tape input must run in real time: the microphone does not slow down
        if (prefs.getBoolean("micOn", false)) step = Speed.REAL;
        emu.speed = Speed.factor(step);
        view.speedBadge = Speed.badge(step);
        // tape
        machine.flashLoad = prefs.getBoolean("flashLoad", true);
        emu.turboLoad = prefs.getBoolean("turboLoad", true);
        machine.tapeVolume = prefs.getBoolean("tapeHear", true) ? 0.25f : 0f;
        machine.micVolume = prefs.getBoolean("micOutSnd", true) ? 0.5f : 0f;
        machine.tapeInvert = prefs.getBoolean("tapeInv", false);
        // memory visualisation
        boolean map = prefs.getBoolean("map", false);
        if (map && !machine.trace.enabled) machine.trace.clear();
        machine.trace.enabled = map;
        machine.trace.setColors(prefs.getInt("colR", 0xFF30FF40), prefs.getInt("colW2", 0xFFFF2A9E),
                prefs.getInt("colRW", 0xFFFFE030), prefs.getInt("colX", 0xFF20E8FF));
        machine.trace.setFlashFrames(prefs.getInt("flash", 5));
        view.mapOn = map;
        machine.trace.layout = Math.max(1, Math.min(4, prefs.getInt("mapW", 1)));
        view.ctrlAlpha = prefs.getInt("ctrlAlpha", 100) / 100f;
        view.config = ControlConfig.load(prefs, program);
        view.title = progTitle();
        machine.releaseAllKeys();
        view.configChanged();
    }

    private void setProgram(String name) {
        program = name == null ? "" : name;
        view.config = ControlConfig.load(prefs, program);
        view.title = progTitle();
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
        if (!p) emu.stepping = false;
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
                case 0: ok = machine.backTime(Spectrum48.CPU_HZ); break;
                case 1: ok = machine.backInstructions(1); break;
                case 2: machine.stepInstruction(); break;
                default: machine.forward(Spectrum48.CPU_HZ); break;
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
        items.add("📂  Открыть файл (.tap .tzx .z80 .sna)…");
        actions.add(this::pickProgram);
        items.add("📚  Мои программы…");
        actions.add(this::showLibrary);
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
        Tape t = machine.tape;
        items.add("📼  Магнитофон" + (t != null ? (t.playing ? " (▶ " + Math.round(t.progress() * 100) + "%)" : " (кассета вставлена)") : "")
                + (prefs.getBoolean("micOn", false) ? " — микрофон ВКЛ" : "") + "…");
        actions.add(this::showTape);
        items.add("🔬  Визуализация памяти…");
        actions.add(this::showVisualization);
        items.add("🔄  Сброс (выключить и включить)");
        actions.add(() -> {
            emu.post(() -> {
                machine.tape = null;
                machine.hardReset();
                machine.releaseAllKeys();
            });
            setProgram("");
        });
        items.add("⚙️  Настройки…");
        actions.add(this::showSettings);
        items.add("❓  Справка");
        actions.add(this::showHelp);
        items.add("ℹ️  Об играх и лицензиях");
        actions.add(this::showAbout);

        show(new AlertDialog.Builder(this)
                .setTitle("ZX Spectrum 48K")
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run()));
    }

    private void showWelcome() {
        show(new AlertDialog.Builder(this)
                .setTitle("ZX Spectrum 48K")
                .setMessage("Включён Sinclair BASIC. Клавиатура — кнопка ⌨ (CAPS и SYM нажимаются один "
                        + "раз и действуют на следующую клавишу).\n\n"
                        + "В комплекте " + Games.LIST.length + " бесплатных игр — меню ☰ → «Игры». "
                        + "Свои файлы .tap .tzx .z80 .sna — «Открыть файл».\n\n"
                        + "Тап по экрану — пауза.")
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
                    if (prefs.getBoolean("micOn", false) && bar.getProgress() != Speed.REAL)
                        toast("Пока включён вход с микрофона, скорость реальная");
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

    // ------------------------------------------------------------------ tape

    private void updateMic() {
        boolean want = resumed && prefs.getBoolean("micOn", false);
        if (want && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (want && !mic.isRunning()) {
            if (mic.start()) {
                machine.tapeInput = mic.decoder;
            } else {
                toast("Не удалось открыть микрофон");
                prefs.edit().putBoolean("micOn", false).apply();
                applySettings();
            }
        } else if (!want && mic.isRunning()) {
            machine.tapeInput = null;
            mic.stop();
        }
        view.tape = mic.isRunning() ? mic.decoder : null;
        view.invalidate();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC) return;
        if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            updateMic();
        } else {
            prefs.edit().putBoolean("micOn", false).apply();
            applySettings();
            toast("Без доступа к микрофону загрузка с магнитофона невозможна");
        }
    }

    private void showTape() {
        boolean micOn = prefs.getBoolean("micOn", false);
        boolean hear = prefs.getBoolean("tapeHear", true);
        boolean outSnd = prefs.getBoolean("micOutSnd", true);
        boolean inv = prefs.getBoolean("tapeInv", false);
        boolean flash = prefs.getBoolean("flashLoad", true);
        boolean turbo = prefs.getBoolean("turboLoad", true);
        Tape t = machine.tape;
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (t != null) {
            items.add(t.playing ? "⏸  Остановить ленту" : "▶  Воспроизвести ленту");
            acts.add(() -> {
                t.playing = !t.playing && !t.finished();
                showTape();
            });
            items.add("⏮  Перемотать в начало");
            acts.add(() -> {
                emu.post(t::rewind);
                toast("Лента перемотана");
                showTape();
            });
            items.add("📋  Блоки на ленте (" + t.blocks.size() + ")");
            acts.add(() -> {
                StringBuilder sb = new StringBuilder();
                int next = t.nextBlock();
                for (int i = 0; i < t.blocks.size(); i++)
                    sb.append(i == next ? "▶ " : "   ").append(i + 1).append(". ").append(t.blocks.get(i).name).append('\n');
                show(new AlertDialog.Builder(this).setTitle("Лента: " + progTitle()).setMessage(sb.toString())
                        .setPositiveButton("OK", (d, w) -> showTape()));
            });
            items.add("⏏  Извлечь кассету");
            acts.add(() -> {
                machine.tape = null;
                showTape();
            });
        }
        items.add("Мгновенная загрузка: " + (flash ? "ВКЛ" : "выкл"));
        acts.add(() -> {
            prefs.edit().putBoolean("flashLoad", !flash).apply();
            applySettings();
            showTape();
        });
        items.add("Ускорять обычную загрузку: " + (turbo ? "ВКЛ" : "выкл"));
        acts.add(() -> {
            prefs.edit().putBoolean("turboLoad", !turbo).apply();
            applySettings();
            showTape();
        });
        items.add("Звук ленты в динамике: " + (hear ? "да" : "нет"));
        acts.add(() -> {
            prefs.edit().putBoolean("tapeHear", !hear).apply();
            applySettings();
            showTape();
        });
        items.add("Вход с микрофона: " + (micOn ? "ВКЛ" : "выкл"));
        acts.add(() -> {
            prefs.edit().putBoolean("micOn", !micOn).apply();
            applySettings();
            updateMic();
            if (!micOn) toast("Вход с микрофона включён; замедление отключено на время загрузки");
            showTape();
        });
        items.add("Запись (SAVE) — звук в динамик: " + (outSnd ? "да" : "нет"));
        acts.add(() -> {
            prefs.edit().putBoolean("micOutSnd", !outSnd).apply();
            applySettings();
            showTape();
        });
        items.add("Полярность входа: " + (inv ? "обратная" : "прямая"));
        acts.add(() -> {
            prefs.edit().putBoolean("tapeInv", !inv).apply();
            applySettings();
            showTape();
        });
        items.add("Как загрузить и записать");
        acts.add(() -> show(new AlertDialog.Builder(this)
                .setTitle("Магнитофон")
                .setMessage("ФАЙЛЫ .TAP / .TZX\n"
                        + "Открываются как кассета: эмулятор сам набирает LOAD \"\" и запускает ленту. "
                        + "С «мгновенной загрузкой» стандартные блоки попадают в память сразу; если игра "
                        + "грузится своим (турбо-) загрузчиком, лента играет по-настоящему, а эмулятор "
                        + "проматывает её ускоренно (⏩). Выключите мгновенную загрузку и ускорение, чтобы "
                        + "увидеть и услышать загрузку как в 1985-м: полосы на бордюре, картинка «жалюзи».\n\n"
                        + "С НАСТОЯЩЕЙ КАССЕТЫ (или другого устройства)\n"
                        + "1. Извлеките кассету-файл и включите «Вход с микрофона».\n"
                        + "2. Наберите LOAD \"\" : клавиша J (на ней LOAD), затем дважды SYM+P (кавычки), ENTER.\n"
                        + "3. Включите воспроизведение. Телефон — у динамика, громкость 70–100 %, тихо вокруг. "
                        + "Лучше — кабель с выхода магнитофона на вход гарнитуры.\n"
                        + "4. Индикатор 📼: полоска — уровень, зелёная точка — есть сигнал ленты.\n"
                        + "Не грузится — меняйте громкость и «Полярность входа».\n\n"
                        + "ЗАПИСЬ\n"
                        + "SAVE \"имя\" (S — это SAVE) — сигнал записи звучит из динамика; запишите его "
                        + "диктофоном или на кассету через кабель, на реальной скорости.\n\n"
                        + "Источник звука микрофона: " + (mic.isRunning() ? mic.sourceName : "—"))
                .setPositiveButton("OK", (d2, w2) -> showTape())));
        show(new AlertDialog.Builder(this)
                .setTitle(t != null ? "Магнитофон: " + progTitle() : "Магнитофон")
                .setItems(items.toArray(new String[0]), (d, which) -> acts.get(which).run())
                .setPositiveButton("Готово", null));
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
                        .setMessage("Показать, как устроена память Спектрума? Каждая область карты будет "
                                + "подсвечена и объяснена: ПЗУ и шрифт, экран и атрибуты, системные переменные, "
                                + "BASIC, стек, графика пользователя.\n\n"
                                + "Экскурсию можно пройти и позже: меню → «Визуализация памяти».")
                        .setPositiveButton("Показать", (d, w) -> startTour())
                        .setNegativeButton("Позже", null));
            } else {
                showVisualization();
            }
        });
        items.add("Байт в строке карты: " + perLine + (perLine == 1 ? " (как у «Специалиста»)" : ""));
        acts.add(() -> {
            String[] names = {"1 байт — шрифты, тайлы 8×8", "2 байта — спрайты 16 точек", "3 байта — спрайты 24 точки",
                "4 байта — спрайты 32 точки"};
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
            emu.post(machine.trace::clear);
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
                .setMessage("Игры: меню → «Игры». Перед стартом показывается подсказка: как выбрать "
                        + "джойстик в меню игры и какие кнопки что делают.\n\n"
                        + "Свои программы: «Открыть файл» — .tap, .tzx (кассеты), .z80, .sna (снимки 48K). "
                        + "Файл сохраняется в «Мои программы».\n\n"
                        + "Джойстик и кнопки: по умолчанию — Kempston (в меню игр выбирайте Kempston). "
                        + "«Настроить кнопки» — назначить любую клавишу или готовую раскладку "
                        + "(Синклер, Курсор, QAOP). Для каждой игры раскладка своя.\n\n"
                        + "Клавиатура: кнопка ⌨. CAPS SHIFT и SYMBOL SHIFT нажимаются один раз и действуют "
                        + "на следующую клавишу (повторное нажатие — отмена). Белым на клавише — ключевое "
                        + "слово BASIC (в режиме K одна клавиша = целое слово), красным — символ с SYM, "
                        + "синим на цифрах — функция с CAPS (CAPS+0 — удалить, CAPS+5…8 — стрелки).\n\n"
                        + "Пауза: нажмите на экран. В паузе вместо названия программы (сбоку от экрана, если телефон лежит) — кнопки: на секунду и на одну команду "
                        + "назад и вперёд. После шага 4 секунды видны луч и текущая команда процессора с регистрами (под панелью, вне картинки). "
                        + "Назад можно уйти на 10 секунд. При шагах по одной команде на карте памяти светится "
                        + "только эта команда: её байты голубым и ячейки, которые она прочитала или записала. "
                        + "Пока открыто меню, эмуляция тоже стоит.\n\n"
                        + "Сохранения: у каждой игры своё. Значок 💾 в списках — у игры есть сохранение.\n\n"
                        + "Скорость: от ×4 до 1 такта в секунду, каждый шаг ползунка — вдвое.\n\n"
                        + "Визуализация памяти: карта всех 64 КБ под экраном, байты вспыхивают при "
                        + "чтении, записи и исполнении; вспышка длится столько же реального времени при любой "
                        + "скорости, но не меньше одной команды; тап по карте — что это за адрес; есть экскурсия.\n\n"
                        + "Файлы: Android/data/com.alexspec.zx/files (папки programs и states), их видно "
                        + "с компьютера по USB.\n\n"
                        + "Телевизор: «Настройки» → «Телевизор» — цветной или чёрно-белый, как у тех, кто "
                        + "подключал Спектрум к чёрно-белому телевизору: цвета становятся оттенками серого "
                        + "(у Спектрума яркость растёт с номером цвета, так что все 8 цветов различимы).\n\n"
                        + "Физическая клавиатура: Shift — CAPS SHIFT, Alt/Ctrl — SYMBOL SHIFT, "
                        + "Backspace — DELETE. Геймпад: крестовина — джойстик, A/B/X/Y — кнопки 1–4.")
                .setPositiveButton("OK", null));
    }

    private void showAbout() {
        StringBuilder sb = new StringBuilder();
        sb.append("Эмулятор ZX Spectrum 48K: процессор Z80 со всеми недокументированными командами "
                + "(проходит тесты zexall и FUSE), задержки ULA такт в такт, бордюр по строкам, бипер, "
                + "Kempston, кассеты TAP/TZX, снимки Z80/SNA.\n\n"
                + "ПЗУ ZX Spectrum © Amstrad; Amstrad разрешила распространять образы ПЗУ для "
                + "использования в эмуляторах.\n\n"
                + "ИГРЫ В КОМПЛЕКТЕ — бесплатные, авторские:\n");
        for (Games.Game g : Games.LIST) sb.append("• ").append(g.title()).append(" — ").append(g.author).append('\n');
        sb.append("\nИгры The Mojon Twins (mojontwins.com) распространяются по лицензии Creative Commons "
                + "BY-NC-SA 3.0: можно копировать и распространять с указанием авторов, некоммерчески, "
                + "производные — под той же лицензией. «Lost in Worlds» (Nihirash, nihirash.itch.io) — "
                + "автор разрешает свободное распространение. Файлы игр не изменялись.");
        show(new AlertDialog.Builder(this)
                .setTitle("Об играх и лицензиях")
                .setMessage(sb.toString())
                .setPositiveButton("OK", null));
    }

    private void showSettings() {
        String[] items = {
            "Кнопок справа: " + view.buttonCount,
            "Джойстик: " + (view.joy8 ? "8 направлений" : "4 направления"),
            "Звук: " + (emu.sound ? "вкл" : "выкл"),
            "Вибрация: " + (view.vibrate ? "вкл" : "выкл"),
            "Телевизор: " + (prefs.getBoolean("mono", false) ? "чёрно-белый" : "цветной"),
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
                        case 3:
                            prefs.edit().putBoolean("vibrate", !view.vibrate).apply();
                            break;
                        default:
                            prefs.edit().putBoolean("mono", !prefs.getBoolean("mono", false)).apply();
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
            items[i] = ControlConfig.SLOT_NAMES[i] + "   →   " + ZxKeys.fullName(code);
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
                                    view.config.set(ControlConfig.PRESETS[w]);
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

    /** a control slot: either a Kempston direction or a key picked on the keyboard */
    private void pickKindFor(int slot) {
        String[] kinds = {"Клавиша на клавиатуре…", "Кемпстон ↑", "Кемпстон ↓", "Кемпстон ←", "Кемпстон →",
            "Кемпстон огонь", "Ничего"};
        int[] codes = {0, Spectrum48.KEMPSTON + 3, Spectrum48.KEMPSTON + 2, Spectrum48.KEMPSTON + 1,
            Spectrum48.KEMPSTON, Spectrum48.KEMPSTON + 4, ZxKeys.NONE};
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
            shown[i] = (stateFile(g.file).exists() ? "💾 " : "      ") + g.title();
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
                .setTitle(g.title())
                .setMessage(g.author + "\n\n" + g.hint + "\n\nКнопки на экране уже настроены для этой игры.")
                .setPositiveButton(st.exists() ? "Заново" : "Играть", (d, w) -> {
                    try {
                        runFile(g.file, readAsset("games/" + g.file));
                    } catch (IOException e) {
                        toast(e.getMessage());
                    }
                })
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

    /** Starts a file: a tape (auto LOAD "") or a snapshot. */
    private void runFile(String name, byte[] data) {
        String e = ext(name);
        emu.post(() -> {
            try {
                switch (e) {
                    case "tap":
                    case "tzx": {
                        Tape t = e.equals("tap") ? Tape.fromTap(data) : Tape.fromTzx(data);
                        t.rewind();
                        machine.tape = t;
                        machine.autoLoad();
                        break;
                    }
                    case "z80":
                        machine.tape = null;
                        machine.loadZ80(data);
                        break;
                    case "sna":
                        machine.tape = null;
                        machine.loadSna(data);
                        break;
                    default:
                        throw new IOException("неизвестный формат (нужен .tap, .tzx, .z80 или .sna)");
                }
                machine.releaseAllKeys();
                ui.post(() -> {
                    setProgram(name);
                    toast((e.equals("tap") || e.equals("tzx")) ? "Загрузка: " + progTitle() : "Запущено: " + progTitle());
                });
            } catch (IOException ex) {
                ui.post(() -> toast(name + ": " + ex.getMessage()));
            }
        });
    }

    private void resumeFrom(String name, File st) {
        try {
            byte[] data = readFile(st);
            emu.post(() -> {
                try {
                    machine.loadState(data);
                    ui.post(() -> {
                        setProgram(name);
                        toast("Продолжаем: " + progTitle());
                    });
                } catch (IOException e) {
                    ui.post(() -> toast("Ошибка: " + e.getMessage()));
                }
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
                    .setMessage("Пока пусто. Откройте файл .tap, .tzx, .z80 или .sna через меню — он появится здесь.")
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
            try {
                runFile(name, readFile(file));
            } catch (IOException e) {
                toast(e.getMessage());
            }
            return;
        }
        show(new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(new String[]{"▶  Продолжить с сохранения (" + when(st) + ")", "⟲  Начать заново"}, (d, w) -> {
                    if (w == 0) {
                        resumeFrom(name, st);
                    } else {
                        try {
                            runFile(name, readFile(file));
                        } catch (IOException e) {
                            toast(e.getMessage());
                        }
                    }
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
            try {
                writeFile(f, machine.saveState());
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
            try {
                machine.loadState(data); // validates the file and restores the machine
                ui.post(() -> {
                    setProgram(prog);
                    try {
                        writeFile(stateFile(prog), data);
                    } catch (IOException e) {
                        android.util.Log.w("ZX", "import copy", e);
                    }
                    toast("Импортировано: " + progTitle());
                });
            } catch (IOException e) {
                ui.post(() -> toast(fname + ": это не сохранение эмулятора"));
            }
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
        } else if (requestCode == REQ_PROGRAM) {
            try {
                byte[] bytes = readUri(uri);
                String name = displayName(uri);
                String e = ext(name);
                if (!(e.equals("tap") || e.equals("tzx") || e.equals("z80") || e.equals("sna"))) {
                    toast(name + ": нужен файл .tap, .tzx, .z80 или .sna");
                    return;
                }
                writeFile(new File(programsDir(), name), bytes);
                runFile(name, bytes);
            } catch (IOException e) {
                toast("Ошибка: " + e.getMessage());
            }
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

    /** Spectrum keys for a host key; Backspace = CAPS + 0 (DELETE). */
    private static int[] zxForKey(KeyEvent ev) {
        int k = ev.getKeyCode();
        if (k >= KeyEvent.KEYCODE_A && k <= KeyEvent.KEYCODE_Z)
            return new int[]{ZxKeys.byLabel(String.valueOf((char) ('A' + k - KeyEvent.KEYCODE_A)))};
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9)
            return new int[]{ZxKeys.byLabel(String.valueOf((char) ('0' + k - KeyEvent.KEYCODE_0)))};
        switch (k) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_START: return new int[]{Spectrum48.ENTER};
            case KeyEvent.KEYCODE_SPACE: return new int[]{Spectrum48.SPACE};
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return new int[]{Spectrum48.CAPS};
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT: return new int[]{Spectrum48.SYM};
            case KeyEvent.KEYCODE_DEL: return new int[]{Spectrum48.CAPS, 20};
            default: return null;
        }
    }

    private final android.util.SparseArray<int[]> physZx = new android.util.SparseArray<>();

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent ev) {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT || keyCode == KeyEvent.KEYCODE_MENU) {
            if (ev.getRepeatCount() == 0) onMenuRequested();
            return true;
        }
        int slot = slotForKey(keyCode);
        int[] zx = slot >= 0 ? null : zxForKey(ev);
        if (slot < 0 && zx == null) return super.onKeyDown(keyCode, ev);
        if (ev.getRepeatCount() > 0 || physKeys.indexOfKey(keyCode) >= 0 || physZx.indexOfKey(keyCode) >= 0) return true;
        if (slot >= 0) {
            physKeys.put(keyCode, slot);
            view.slotKey(slot, true);
        } else {
            physZx.put(keyCode, zx);
            for (int c : zx) machine.keyDown(c);
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
        int zi = physZx.indexOfKey(keyCode);
        if (zi >= 0) {
            for (int c : physZx.valueAt(zi)) machine.keyUp(c);
            physZx.removeAt(zi);
            return true;
        }
        return super.onKeyUp(keyCode, ev);
    }

    // ------------------------------------------------------------------ misc

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
