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

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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

    private File programsDir() {
        File d = new File(getFilesDir(), "programs");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private File stateFile() {
        File d = new File(getFilesDir(), "states");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return new File(d, (program.isEmpty() ? "_monitor" : program) + ".sav");
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
        emu.turbo = false;
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
        items.add("💾  Быстрое сохранение");
        actions.add(this::quickSave);
        items.add("📥  Быстрая загрузка");
        actions.add(this::quickLoad);
        items.add(emu.turbo ? "⏩  Турбо: ВКЛ (выключить)" : "⏩  Турбо ×5");
        actions.add(() -> {
            emu.turbo = !emu.turbo;
            toast(emu.turbo ? "Турбо включено" : "Турбо выключено");
        });
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

        new AlertDialog.Builder(this)
                .setTitle("Специалист")
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    @Override
    public void onKeyboardToggled(boolean visible) {
        machine.releaseAllKeys();
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Справка")
                .setMessage("Запуск: после включения на запрос «PROGRAM ?» нажмите любую из клавиш F1–F11, "
                        + "затем ВК — откроется Монитор.\n\n"
                        + "Программы: меню → «Загрузить программу», выберите файл .rks. "
                        + "Он запустится сразу и сохранится в «Мои программы».\n\n"
                        + "Кнопки: меню → «Настроить кнопки». Для каждой программы раскладка запоминается отдельно.\n\n"
                        + "Клавиатура: кнопка ⌨. НР (Shift) нажимается один раз и действует на следующую клавишу. "
                        + "Р/Л переключает латиницу и кириллицу.\n\n"
                        + "Состояние машины сохраняется автоматически при выходе.")
                .setPositiveButton("OK", null)
                .show();
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
        new AlertDialog.Builder(this)
                .setTitle("Настройки")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: {
                            String[] counts = {"1", "2", "3", "4"};
                            new AlertDialog.Builder(this)
                                    .setTitle("Кнопок справа")
                                    .setSingleChoiceItems(counts, view.buttonCount - 1, (d2, w) -> {
                                        prefs.edit().putInt("buttons", w + 1).apply();
                                        d2.dismiss();
                                        applySettings();
                                        showSettings();
                                    })
                                    .show();
                            return;
                        }
                        case 1:
                            prefs.edit().putBoolean("joy8", !view.joy8).apply();
                            break;
                        case 2: {
                            new AlertDialog.Builder(this)
                                    .setTitle("Цвет экрана")
                                    .setSingleChoiceItems(colorNames, color, (d2, w) -> {
                                        prefs.edit().putInt("color", w).apply();
                                        d2.dismiss();
                                        applySettings();
                                        showSettings();
                                    })
                                    .show();
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
                .setPositiveButton("Готово", null)
                .show();
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
                })
                .setPositiveButton("Готово", null);
        if (!program.isEmpty()) {
            b.setNeutralButton("Сделать общими", (d, w) -> {
                view.config.save(prefs, ControlConfig.DEFAULT_PROFILE);
                toast("Эта раскладка теперь используется по умолчанию");
            });
        }
        b.show();
    }

    @Override
    public void onKeyPicked(int code) {
        if (pickSlot >= 0) {
            view.config.keys[pickSlot] = code;
            view.config.save(prefs, program);
            pickSlot = -1;
            machine.releaseAllKeys();
            view.configChanged();
            showMapping();
        }
    }

    @Override
    public void onPickCancelled() {
        pickSlot = -1;
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
            new AlertDialog.Builder(this)
                    .setTitle("Мои программы")
                    .setMessage("Пока пусто. Загрузите программу через меню — она появится здесь.")
                    .setPositiveButton("Загрузить…", (d, w) -> pickProgram())
                    .setNegativeButton("Закрыть", null)
                    .show();
            return;
        }
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) names[i] = files[i].getName();
        new AlertDialog.Builder(this)
                .setTitle("Мои программы")
                .setItems(names, (d, which) -> {
                    if (!machine.isRomLoaded()) {
                        askForRom();
                        return;
                    }
                    try {
                        runProgram(names[which], readFile(files[which]));
                    } catch (IOException e) {
                        toast(e.getMessage());
                    }
                })
                .setNeutralButton("Удалить…", (d, w) -> deletePrograms(files, names))
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void deletePrograms(File[] files, String[] names) {
        boolean[] checked = new boolean[files.length];
        new AlertDialog.Builder(this)
                .setTitle("Удалить программы")
                .setMultiChoiceItems(names, checked, (d, w, c) -> checked[w] = c)
                .setPositiveButton("Удалить", (d, w) -> {
                    int n = 0;
                    for (int i = 0; i < files.length; i++) {
                        if (checked[i] && files[i].delete()) {
                            ControlConfig.remove(prefs, names[i]);
                            n++;
                        }
                    }
                    toast("Удалено: " + n);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void quickSave() {
        if (!machine.isRomLoaded()) return;
        final File f = stateFile();
        emu.post(() -> {
            try {
                writeFile(f, machine.saveState());
                ui.post(() -> toast("Сохранено"));
            } catch (IOException e) {
                ui.post(() -> toast("Ошибка: " + e.getMessage()));
            }
        });
    }

    private void quickLoad() {
        File f = stateFile();
        if (!f.exists()) {
            toast("Сохранения пока нет");
            return;
        }
        try {
            byte[] data = readFile(f);
            emu.post(() -> {
                try {
                    machine.loadState(data);
                    ui.post(() -> toast("Загружено"));
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
        new AlertDialog.Builder(this)
                .setTitle("Нужно ПЗУ")
                .setMessage("Выберите файл ПЗУ монитора «Специалиста» (обычно 2–12 КБ, например spec_m2.rom). "
                        + "Если ПЗУ состоит из нескольких файлов, выберите их все сразу — они склеятся по порядку имён.")
                .setPositiveButton("Выбрать файл", (d, w) -> pickRom())
                .setNegativeButton("Позже", null)
                .show();
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
            return SpecKeys.byLabel2(String.valueOf((char) ('A' + k - KeyEvent.KEYCODE_A)));
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
            case KeyEvent.KEYCODE_LEFT_BRACKET: return SpecKeys.byLabel2("[");
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return SpecKeys.byLabel2("]");
            case KeyEvent.KEYCODE_BACKSLASH: return SpecKeys.byLabel2("\\");
            case KeyEvent.KEYCODE_APOSTROPHE: return SpecKeys.byLabel(":");
            case KeyEvent.KEYCODE_GRAVE: return SpecKeys.byLabel2("@");
            case KeyEvent.KEYCODE_EQUALS: return SpecKeys.byLabel2("^");
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
