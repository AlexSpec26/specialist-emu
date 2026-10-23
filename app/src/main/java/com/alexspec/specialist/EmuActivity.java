package com.alexspec.specialist;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
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

import com.alexspec.specialist.core.SpecKeys;
import com.alexspec.specialist.core.Specialist;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class EmuActivity extends Activity implements MainView.Host {
    private static final int REQ_PROGRAM = 1;
    private static final int REQ_ROM = 2;
    private static final int REQ_EXPORT_STATE = 3;
    private static final int REQ_IMPORT_STATE = 4;
    private static final int MAX_FILE = 128 * 1024;

    private EmuThread emu;
    private Specialist machine;
    private MainView view;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Program currently running ("" = none / monitor); also the control profile name. */
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
        // Draw edge to edge on every Android version; MainView keeps content clear of
        // rounded corners and the camera hole using the window insets.
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);

        emu = new EmuThread(() -> {
            MainView v = view;
            if (v != null) v.postInvalidateOnAnimation();
        });
        machine = emu.machine;
        view = new MainView(this, emu, this);
        setContentView(view);
        applySettings();

        File rom = romFile();
        if (rom.exists()) {
            try {
                byte[] data = readFile(rom);
                String saved = prefs.getString("autosaveProgram", null);
                File auto = autosaveFile();
                final byte[] autoData = (saved != null && auto.exists()) ? readFile(auto) : null;
                emu.post(() -> {
                    machine.loadRom(data);
                    machine.hardReset();
                    if (autoData != null) {
                        try {
                            machine.loadState(autoData);
                        } catch (IOException e) {
                            android.util.Log.w("Specialist", "autosave", e);
                        }
                    }
                });
                if (autoData != null) setProgram(saved);
            } catch (IOException e) {
                toast("Не удалось прочитать ПЗУ: " + e.getMessage());
            }
        }
        migrateStorage();
        emu.start();
        if (!rom.exists()) ui.post(this::askForRom);
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
        if (machine.isRomLoaded()) {
            final String prog = program;
            emu.post(() -> {
                try {
                    writeFile(autosaveFile(), machine.saveState());
                    prefs.edit().putString("autosaveProgram", prog).apply();
                } catch (IOException e) {
                    android.util.Log.w("Specialist", "autosave", e);
                }
            });
        }
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

    private File romFile() {
        return new File(getFilesDir(), "rom.bin");
    }

    private File autosaveFile() {
        return new File(getFilesDir(), "autosave.sav");
    }

    /**
     * Programs and saves live in the app's external folder
     * (Android/data/com.alexspec.specialist/files), which is visible over USB.
     */
    private File dataRoot() {
        File ext = getExternalFilesDir(null);
        return ext != null ? ext : getFilesDir();
    }

    /** One-time move of programs/saves from the old internal location. */
    private void migrateStorage() {
        File root = dataRoot();
        if (root.equals(getFilesDir())) return;
        for (String sub : new String[]{"programs", "states"}) {
            File[] old = new File(getFilesDir(), sub).listFiles();
            if (old == null) continue;
            File dst = new File(root, sub);
            //noinspection ResultOfMethodCallIgnored
            dst.mkdirs();
            for (File f : old) {
                File t = new File(dst, f.getName());
                try {
                    if (!t.exists()) writeFile(t, readFile(f));
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                } catch (IOException e) {
                    android.util.Log.w("Specialist", "migrate " + f, e);
                }
            }
        }
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

    /** Quick-save slot belonging to one program (by its file name); "" is the bare monitor. */
    private File stateFile(String prog) {
        File d = new File(dataRoot(), "states");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return new File(d, (prog.isEmpty() ? "_monitor" : prog) + ".sav");
    }

    private String progTitle() {
        return program.isEmpty() ? "Монитор" : program;
    }

    private static String when(File f) {
        return new java.text.SimpleDateFormat("d MMM, HH:mm", new java.util.Locale("ru"))
                .format(new java.util.Date(f.lastModified()));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
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
        if (name == null) name = "program.rks";
        return name.replaceAll("[/\\\\:*?\"<>|]", "_");
    }

    // ------------------------------------------------------------------ settings

    private void applySettings() {
        view.buttonCount = prefs.getInt("buttons", 3);
        view.joy8 = prefs.getBoolean("joy8", false);
        view.vibrate = prefs.getBoolean("vibrate", true);
        emu.colorMode = prefs.getInt("color", Specialist.COLOR_WHITE);
        emu.sound = prefs.getBoolean("sound", true);
        int slow = Math.max(1, Math.min(100, prefs.getInt("slow", 1)));
        emu.speed = 1f / slow;
        view.speed = 1f / slow;
        // memory visualisation
        boolean map = prefs.getBoolean("map", false);
        if (map && !machine.trace.enabled) machine.trace.clear();
        machine.trace.enabled = map;
        machine.trace.setColors(prefs.getInt("colR", 0xFF30FF40), prefs.getInt("colW", 0xFFFF3030),
                prefs.getInt("colRW", 0xFFFFE030), prefs.getInt("colX", 0xFF20E8FF));
        machine.trace.setFlashFrames(prefs.getInt("flash", 5));
        view.mapOn = map;
        view.ctrlAlpha = prefs.getInt("ctrlAlpha", 100) / 100f;
        view.config = ControlConfig.load(prefs, program);
        view.title = program;
        machine.releaseAllKeys();
        view.configChanged();
    }

    private void setProgram(String name) {
        program = name == null ? "" : name;
        view.config = ControlConfig.load(prefs, program);
        view.title = program;
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
        view.paused = tapPaused || view.isPicking();
        view.invalidate();
    }

    @Override
    public void onScreenTapped() {
        tapPaused = !tapPaused;
        updatePause();
    }

    // ------------------------------------------------------------------ menu

    @Override
    public void onMenuRequested() {
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        items.add("📂  Загрузить программу (.rks)…");
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
        int slowNow = Math.round(1f / emu.speed);
        items.add(slowNow <= 1 ? "⏱  Замедление: нет (реальная скорость)…" : "⏱  Замедление: в " + slowNow + " раз…");
        actions.add(this::showSpeed);
        items.add("🔬  Визуализация памяти…");
        actions.add(this::showVisualization);
        items.add("🔄  Перезапуск (сброс)");
        actions.add(() -> emu.post(() -> {
            machine.reset();
            machine.releaseAllKeys();
        }));
        items.add("🧹  Холодный старт (очистить память)");
        actions.add(() -> {
            emu.post(() -> {
                machine.hardReset();
                machine.releaseAllKeys();
            });
            setProgram("");
        });
        items.add("⚙️  Настройки…");
        actions.add(this::showSettings);
        items.add("🔧  Сменить ПЗУ…");
        actions.add(this::pickRom);
        items.add("❓  Справка");
        actions.add(this::showHelp);

        show(new AlertDialog.Builder(this)
                .setTitle("Специалист")
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
                );
    }

    private void showSpeed() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        android.widget.TextView hint = new android.widget.TextView(this);
        hint.setText("Во сколько раз медленнее настоящего «Специалиста»: 1 — реальная скорость, "
                + "100 — в сто раз медленнее (удобно рассматривать карту памяти).");
        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(Math.max(1, Math.round(1f / emu.speed))));
        input.setSelectAllOnFocus(true);
        input.setTextSize(28);
        input.setGravity(android.view.Gravity.CENTER);
        box.addView(hint);
        box.addView(input);
        AlertDialog d = show(new AlertDialog.Builder(this)
                .setTitle("Замедление")
                .setView(box)
                .setPositiveButton("OK", (dlg, w) -> {
                    int v;
                    try {
                        v = Integer.parseInt(input.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        v = 1;
                    }
                    v = Math.max(1, Math.min(100, v));
                    prefs.edit().putInt("slow", v).apply();
                    applySettings();
                    toast(v == 1 ? "Реальная скорость" : "Замедление в " + v + " раз");
                })
                .setNeutralButton("1 — реальная", (dlg, w) -> {
                    prefs.edit().putInt("slow", 1).apply();
                    applySettings();
                })
                .setNegativeButton("Отмена", null));
        input.requestFocus();
        if (d.getWindow() != null)
            d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    private static final String[] COLOR_NAMES = {"зелёный", "красный", "жёлтый", "голубой", "синий",
            "пурпурный", "оранжевый", "белый"};
    private static final int[] COLOR_VALUES = {0xFF30FF40, 0xFFFF3030, 0xFFFFE030, 0xFF20E8FF, 0xFF4070FF,
            0xFFFF40FF, 0xFFFF9020, 0xFFFFFFFF};

    private static String colorName(int c) {
        for (int i = 0; i < COLOR_VALUES.length; i++) if (COLOR_VALUES[i] == c) return COLOR_NAMES[i];
        return String.format("#%06X", c & 0xFFFFFF);
    }

    private void showVisualization() {
        boolean on = prefs.getBoolean("map", false);
        int flash = prefs.getInt("flash", 5);
        String[] keys = {"colR", "colW", "colRW", "colX"};
        int[] defs = {0xFF30FF40, 0xFFFF3030, 0xFFFFE030, 0xFF20E8FF};
        String[] what = {"Чтение", "Запись", "Чтение и запись", "Исполнение (команды и операнды)"};
        String[] items = {
            "Карта памяти: " + (on ? "ВКЛ" : "выкл"),
            what[0] + ": " + colorName(prefs.getInt(keys[0], defs[0])),
            what[1] + ": " + colorName(prefs.getInt(keys[1], defs[1])),
            what[2] + ": " + colorName(prefs.getInt(keys[2], defs[2])),
            what[3] + ": " + colorName(prefs.getInt(keys[3], defs[3])),
            String.format(java.util.Locale.US, "Вспышка: %.1f с", flash / 50f),
            "Прозрачность джойстика и кнопок: " + (100 - prefs.getInt("ctrlAlpha", 100)) + "%",
            "Стереть следы обращений",
            "Как пользоваться",
        };
        show(new AlertDialog.Builder(this)
                .setTitle("Визуализация памяти")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        boolean nv = !on;
                        prefs.edit().putBoolean("map", nv).apply();
                        // joystick over the map is much nicer half-transparent
                        if (nv && !prefs.contains("ctrlAlpha")) prefs.edit().putInt("ctrlAlpha", 50).apply();
                        applySettings();
                        if (nv && view.getWidth() > view.getHeight())
                            toast("Карта памяти показывается в портретной ориентации");
                        showVisualization();
                    } else if (which >= 1 && which <= 4) {
                        String key = keys[which - 1];
                        int cur = prefs.getInt(key, defs[which - 1]);
                        int sel = -1;
                        for (int i = 0; i < COLOR_VALUES.length; i++) if (COLOR_VALUES[i] == cur) sel = i;
                        show(new AlertDialog.Builder(this)
                                .setTitle(what[which - 1])
                                .setSingleChoiceItems(COLOR_NAMES, sel, (d2, w) -> {
                                    prefs.edit().putInt(key, COLOR_VALUES[w]).apply();
                                    d2.dismiss();
                                    applySettings();
                                    showVisualization();
                                }));
                    } else if (which == 5) {
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
                    } else if (which == 6) {
                        showAlpha();
                    } else if (which == 7) {
                        emu.post(machine.trace::clear);
                        toast("Следы стёрты");
                    } else {
                        show(new AlertDialog.Builder(this)
                                .setTitle("Карта памяти")
                                .setMessage("Под экраном показаны все 64 КБ: один бит — одна точка, байт — полоска "
                                        + "из 8 точек. Адреса идут сверху вниз столбцами по 256 байт, как в экранной "
                                        + "памяти «Специалиста»: номер столбца — старший байт адреса. "
                                        + "Верхняя половина — 0000–7FFF, нижняя — 8000–FFFF.\n\n"
                                        + "Байты вспыхивают при чтении, записи и исполнении. "
                                        + "Коснитесь карты — появится адрес, значение и что это за область.\n\n"
                                        + "Совет: пауза (тап по экрану) и замедление помогают рассмотреть, "
                                        + "как «шевелится» код.")
                                .setPositiveButton("OK", (d2, w) -> showVisualization()));
                    }
                })
                .setPositiveButton("Готово", null));
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
                .setMessage("Запуск: после включения на запрос «PROGRAM ?» нажмите любую из клавиш F1–F11, "
                        + "затем ВК — откроется Монитор.\n\n"
                        + "Программы: меню → «Загрузить программу», выберите файл .rks. "
                        + "Он запустится сразу и сохранится в «Мои программы».\n\n"
                        + "Кнопки: меню → «Настроить кнопки». Для каждой программы раскладка запоминается отдельно.\n\n"
                        + "Клавиатура: кнопка ⌨. НР (Shift) нажимается один раз и действует на следующую клавишу. "
                        + "Р/Л переключает латиницу и кириллицу.\n\n"
                        + "Пауза: нажмите на экран «Специалиста». Пока открыто меню, эмуляция тоже стоит.\n\n"
                        + "Сохранения: у каждой программы своё. В «Моих программах» значок 💾 — "
                        + "у программы есть сохранение, при запуске можно продолжить с него.\n\n"
                        + "Замедление: меню → «Замедление», от 1 (реальная скорость) до 100.\n\n"
                        + "Визуализация памяти: карта всех 64 КБ под экраном, байты вспыхивают при "
                        + "чтении, записи и исполнении; тап по карте — что это за адрес.\n\n"
                        + "Файлы: программы и сохранения лежат в Android/data/com.alexspec.specialist/files "
                        + "(папки programs и states), их видно с компьютера по USB. "
                        + "Через меню можно экспортировать или импортировать сохранение в любую папку.\n\n"
                        + "Состояние машины сохраняется автоматически при выходе.")
                .setPositiveButton("OK", null)
                );
    }

    private void showSettings() {
        String[] colorNames = {"белый", "зелёный", "янтарный", "цветной (8 цветов)"};
        int color = prefs.getInt("color", Specialist.COLOR_WHITE);
        String[] items = {
            "Кнопок справа: " + view.buttonCount,
            "Джойстик: " + (view.joy8 ? "8 направлений" : "4 направления"),
            "Цвет экрана: " + colorNames[Math.max(0, Math.min(3, color))],
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
                                    })
                                    );
                            return;
                        }
                        case 1:
                            prefs.edit().putBoolean("joy8", !view.joy8).apply();
                            break;
                        case 2: {
                            show(new AlertDialog.Builder(this)
                                    .setTitle("Цвет экрана")
                                    .setSingleChoiceItems(colorNames, color, (d2, w) -> {
                                        prefs.edit().putInt("color", w).apply();
                                        d2.dismiss();
                                        applySettings();
                                        showSettings();
                                    })
                                    );
                            return;
                        }
                        case 3:
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
        String[] items = new String[slots + 1];
        for (int i = 0; i < slots; i++) {
            int code = view.config.keys[i];
            items[i] = ControlConfig.SLOT_NAMES[i] + "   →   " + (code == SpecKeys.NONE ? "—" : SpecKeys.label(code));
        }
        items[slots] = "↺  Сбросить к стандартным";
        String who = program.isEmpty() ? "общие" : program;
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Кнопки: " + who)
                .setItems(items, (d, which) -> {
                    if (which == slots) {
                        if (program.isEmpty()) view.config.setDefaults();
                        else {
                            ControlConfig.remove(prefs, program);
                            view.config = ControlConfig.load(prefs, program);
                        }
                        if (program.isEmpty()) view.config.save(prefs, program);
                        machine.releaseAllKeys();
                        view.configChanged();
                        showMapping();
                        return;
                    }
                    pickSlot = which;
                    view.startKeyPick("Нажмите клавишу для «" + ControlConfig.SLOT_NAMES[which] + "»");
                    updatePause();
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

    // ------------------------------------------------------------------ programs

    private void pickProgram() {
        if (!machine.isRomLoaded()) {
            askForRom();
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_PROGRAM);
    }

    private void runProgram(String name, byte[] data) {
        emu.post(() -> {
            try {
                machine.loadRks(data, true);
                ui.post(() -> {
                    setProgram(name);
                    toast("Запущено: " + name);
                });
            } catch (IOException e) {
                ui.post(() -> toast(name + ": " + e.getMessage()));
            }
        });
    }

    private void showLibrary() {
        File[] files = programsDir().listFiles();
        if (files == null || files.length == 0) {
            show(new AlertDialog.Builder(this)
                    .setTitle("Мои программы")
                    .setMessage("Пока пусто. Загрузите программу через меню — она появится здесь.")
                    .setPositiveButton("Загрузить…", (d, w) -> pickProgram())
                    .setNegativeButton("Закрыть", null)
                    );
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
                .setItems(shown, (d, which) -> {
                    if (!machine.isRomLoaded()) {
                        askForRom();
                        return;
                    }
                    launchFromLibrary(names[which], files[which]);
                })
                .setNeutralButton("Удалить…", (d, w) -> deletePrograms(files, names))
                .setNegativeButton("Закрыть", null)
                );
    }

    private void launchFromLibrary(String name, File file) {
        File st = stateFile(name);
        if (!st.exists()) {
            try {
                runProgram(name, readFile(file));
            } catch (IOException e) {
                toast(e.getMessage());
            }
            return;
        }
        show(new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(new String[]{"▶  Продолжить с сохранения (" + when(st) + ")", "⟲  Начать заново"}, (d, w) -> {
                    try {
                        if (w == 0) {
                            byte[] data = readFile(st);
                            emu.post(() -> {
                                try {
                                    machine.loadState(data);
                                    ui.post(() -> {
                                        setProgram(name);
                                        toast("Продолжаем: " + name);
                                    });
                                } catch (IOException e) {
                                    ui.post(() -> toast("Ошибка: " + e.getMessage()));
                                }
                            });
                        } else {
                            runProgram(name, readFile(file));
                        }
                    } catch (IOException e) {
                        toast(e.getMessage());
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
                .setNegativeButton("Отмена", null)
                );
    }

    private void quickSave() {
        if (!machine.isRomLoaded()) return;
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
        if (!machine.isRomLoaded()) {
            askForRom();
            return;
        }
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
        String base = fname.toLowerCase(java.util.Locale.ROOT).endsWith(".sav")
                ? fname.substring(0, fname.length() - 4) : fname;
        final String prog = "_monitor".equals(base) ? "" : base;
        emu.post(() -> {
            try {
                machine.loadState(data); // validates the file and restores the machine
                ui.post(() -> {
                    setProgram(prog);
                    try {
                        writeFile(stateFile(prog), data);
                    } catch (IOException e) {
                        android.util.Log.w("Specialist", "import copy", e);
                    }
                    toast("Импортировано: " + progTitle());
                });
            } catch (IOException e) {
                ui.post(() -> toast(fname + ": это не сохранение эмулятора"));
            }
        });
    }

    private void quickLoad() {
        File f = stateFile();
        if (!f.exists()) {
            toast("Для «" + progTitle() + "» сохранения пока нет");
            return;
        }
        try {
            byte[] data = readFile(f);
            emu.post(() -> {
                try {
                    machine.loadState(data);
                    ui.post(() -> toast("Загружено: " + progTitle()));
                } catch (IOException e) {
                    ui.post(() -> toast("Ошибка: " + e.getMessage()));
                }
            });
        } catch (IOException e) {
            toast("Ошибка: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ ROM

    private void askForRom() {
        show(new AlertDialog.Builder(this)
                .setTitle("Нужно ПЗУ")
                .setMessage("Выберите файл ПЗУ монитора «Специалиста» (обычно 2–12 КБ, например spec_m2.rom). "
                        + "Если ПЗУ состоит из нескольких файлов, выберите их все сразу — они склеятся по порядку имён.")
                .setPositiveButton("Выбрать файл", (d, w) -> pickRom())
                .setNegativeButton("Позже", null)
                );
    }

    private void pickRom() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_ROM);
    }

    private void installRom(List<Uri> uris) {
        try {
            List<String[]> named = new ArrayList<>();
            for (Uri u : uris) named.add(new String[]{displayName(u), u.toString()});
            named.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            for (String[] n : named) bos.write(readUri(Uri.parse(n[1])));
            byte[] rom = bos.toByteArray();
            if (rom.length == 0) throw new IOException("пустой файл");
            if (rom.length > Specialist.ROM_SIZE) {
                toast("ПЗУ больше 12 КБ — использованы первые 12 КБ");
                rom = Arrays.copyOf(rom, Specialist.ROM_SIZE);
            }
            writeFile(romFile(), rom);
            final byte[] data = rom;
            emu.post(() -> {
                machine.loadRom(data);
                machine.hardReset();
            });
            setProgram("");
            toast("ПЗУ установлено (" + rom.length + " байт). Нажмите F1, затем ВК");
        } catch (IOException e) {
            toast("Не удалось загрузить ПЗУ: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_ROM) {
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (!uris.isEmpty()) installRom(uris);
        } else if (requestCode == REQ_EXPORT_STATE && data.getData() != null) {
            onExportChosen(data.getData());
        } else if (requestCode == REQ_IMPORT_STATE && data.getData() != null) {
            onImportChosen(data.getData());
        } else if (requestCode == REQ_PROGRAM && data.getData() != null) {
            Uri uri = data.getData();
            try {
                byte[] bytes = readUri(uri);
                String name = displayName(uri);
                writeFile(new File(programsDir(), name), bytes);
                runProgram(name, bytes);
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

    private static int specForKey(KeyEvent ev) {
        int k = ev.getKeyCode();
        if (k >= KeyEvent.KEYCODE_A && k <= KeyEvent.KEYCODE_Z)
            return SpecKeys.byLabel(String.valueOf((char) ('A' + k - KeyEvent.KEYCODE_A)));
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9)
            return SpecKeys.byLabel(String.valueOf((char) ('0' + k - KeyEvent.KEYCODE_0)));
        if (k >= KeyEvent.KEYCODE_F1 && k <= KeyEvent.KEYCODE_F11)
            return SpecKeys.byLabel("F" + (k - KeyEvent.KEYCODE_F1 + 1));
        switch (k) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_START: return SpecKeys.ENTER;
            case KeyEvent.KEYCODE_DEL: return SpecKeys.BACKSPACE;
            case KeyEvent.KEYCODE_SPACE: return SpecKeys.SPACE;
            case KeyEvent.KEYCODE_ESCAPE: return SpecKeys.ESC;
            case KeyEvent.KEYCODE_TAB: return SpecKeys.TAB;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return SpecKeys.SHIFT;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT: return SpecKeys.LANG;
            case KeyEvent.KEYCODE_F12: return SpecKeys.CLEAR;
            case KeyEvent.KEYCODE_MOVE_HOME: return SpecKeys.HOME;
            case KeyEvent.KEYCODE_MOVE_END: return SpecKeys.LF;
            case KeyEvent.KEYCODE_PERIOD: return SpecKeys.byLabel(".");
            case KeyEvent.KEYCODE_COMMA: return SpecKeys.byLabel(",");
            case KeyEvent.KEYCODE_MINUS: return SpecKeys.byLabel("-");
            case KeyEvent.KEYCODE_SEMICOLON: return SpecKeys.byLabel(";");
            case KeyEvent.KEYCODE_SLASH: return SpecKeys.byLabel("/");
            case KeyEvent.KEYCODE_LEFT_BRACKET: return SpecKeys.byLabel("[");
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return SpecKeys.byLabel("]");
            case KeyEvent.KEYCODE_BACKSLASH: return SpecKeys.byLabel("\\");
            case KeyEvent.KEYCODE_APOSTROPHE: return SpecKeys.byLabel(":");
            case KeyEvent.KEYCODE_GRAVE: return SpecKeys.byLabel("@");
            case KeyEvent.KEYCODE_EQUALS: return SpecKeys.byLabel("^");
            default: return SpecKeys.NONE;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent ev) {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT || keyCode == KeyEvent.KEYCODE_MENU) {
            if (ev.getRepeatCount() == 0) onMenuRequested();
            return true;
        }
        int slot = slotForKey(keyCode);
        int spec = slot >= 0 ? -1 : specForKey(ev);
        if (slot < 0 && spec == SpecKeys.NONE) return super.onKeyDown(keyCode, ev);
        if (ev.getRepeatCount() > 0 || physKeys.indexOfKey(keyCode) >= 0) return true;
        if (slot >= 0) {
            physKeys.put(keyCode, -1000 - slot);
            view.slotKey(slot, true);
        } else {
            physKeys.put(keyCode, spec);
            machine.keyDown(spec);
        }
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent ev) {
        int idx = physKeys.indexOfKey(keyCode);
        if (idx < 0) return super.onKeyUp(keyCode, ev);
        int v = physKeys.valueAt(idx);
        physKeys.removeAt(idx);
        if (v <= -1000) view.slotKey(-1000 - v, false);
        else machine.keyUp(v);
        return true;
    }

    // ------------------------------------------------------------------ misc

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
