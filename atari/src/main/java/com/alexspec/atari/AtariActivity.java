package com.alexspec.atari;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.SparseIntArray;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The only screen of the app: top bar (menu, title, pause, keyboard) and {@link PlayView}.
 * Handles the menu, file import into the private library, settings and physical input.
 *
 * Private files (getFilesDir()):
 *   games/&lt;sha256&gt;.&lt;ext&gt;   imported programs; display names in preferences "name:&lt;file&gt;"
 *   states/&lt;profile&gt;-quick|auto.state   save states (see AtariRunner)
 *   ATARIXL.ROM, ATARIBAS.ROM   the user's own OS/BASIC; without them the free Altirra ones are used
 *   demo.xex   the bundled game, copied from assets
 * GPL-2.0-or-later, see atari/COPYING.
 */
public final class AtariActivity extends Activity {
    private static final int PICK_GAME = 1, PICK_OS = 2, PICK_BASIC = 3;
    private static final int OS_SIZE = 16384, BASIC_SIZE = 8192;
    private static final List<String> GAME_TYPES =
            Arrays.asList("xex", "com", "exe", "atr", "xfd", "car", "cas", "atx", "dcm");

    private AtariRunner runner;
    private PlayView view;
    private TextView title;
    private Button pauseButton;
    private SharedPreferences prefs;
    private File root;
    private boolean resumed, userPaused;
    /** Number of open dialogs; the machine pauses while any is open. */
    private int dialogs;
    /** What is running now: file path ("" = BASIC) and its display name. */
    private volatile String current = "", currentTitle = "Atari BASIC";
    /** Physical key code → libretro key sent on press, so the release matches it. */
    private final SparseIntArray physical = new SparseIntArray();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        root = getFilesDir();
        prefs = getSharedPreferences("atari", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        runner = new AtariRunner(root, new AtariRunner.Listener() {
            @Override public void frame() {
                if (view != null) view.postInvalidateOnAnimation();
            }

            @Override public void message(String text) {
                if (!isDestroyed()) toast(text);
            }
        });
        runner.sound = prefs.getBoolean("sound", true);
        setContentView(buildLayout());
        runner.start();

        runner.post(() -> {
            migrateInspectorRoms();
            copy(getAssets().open("demos/catch-the-star.xex"), new File(root, "demo.xex"), 65536);
            String last = prefs.getString("path", "");
            if (!last.isEmpty() && !new File(last).exists()) last = "";
            open(last, last.isEmpty() ? "Atari BASIC" : prefs.getString("title", "Atari"), true);
        });
    }

    private LinearLayout buildLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(0xff101018);
        layout.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout bar = new LinearLayout(this);
        Button menu = new Button(this);
        menu.setText("☰");
        menu.setOnClickListener(v -> menu());
        bar.addView(menu, new LinearLayout.LayoutParams(-2, -2));

        title = new TextView(this);
        title.setTextColor(0xffe0e7ff);
        title.setTextSize(15);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        bar.addView(title, new LinearLayout.LayoutParams(0, -1, 1));

        pauseButton = new Button(this);
        pauseButton.setText("❚❚");
        pauseButton.setOnClickListener(v -> {
            userPaused = !userPaused;
            updatePause();
        });
        bar.addView(pauseButton);

        Button keyboard = new Button(this);
        keyboard.setText("⌨");
        keyboard.setOnClickListener(v -> {
            view.releaseAll();
            view.keyboard = !view.keyboard;
            view.invalidate();
        });
        bar.addView(keyboard);
        layout.addView(bar);

        view = new PlayView(this, runner);
        layout.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        return layout;
    }

    /**
     * Builds up to atari-build-5 had a separate Java CPU inspector that kept the user's ROMs as os.rom / basic.rom.
     * The inspector is gone (ADR-022); hand those ROMs over to the game machine once.
     */
    private void migrateInspectorRoms() throws IOException {
        File oldOs = new File(root, "os.rom"), newOs = new File(root, "ATARIXL.ROM");
        if (oldOs.exists() && !newOs.exists()) copy(new FileInputStream(oldOs), newOs, OS_SIZE);
        File oldBasic = new File(root, "basic.rom"), newBasic = new File(root, "ATARIBAS.ROM");
        if (oldBasic.exists() && !newBasic.exists()) copy(new FileInputStream(oldBasic), newBasic, BASIC_SIZE);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    /** The machine runs only while the app is visible, not paused by the user and no dialog is open. */
    private void updatePause() {
        boolean p = !resumed || userPaused || dialogs > 0;
        runner.pause(p);
        view.paused = p;
        pauseButton.setText(userPaused ? "▶" : "❚❚");
        view.invalidate();
    }

    private void show(AlertDialog.Builder builder) {
        dialogs++;
        updatePause();
        AlertDialog d = builder.create();
        d.setOnDismissListener(v -> {
            dialogs--;
            updatePause();
        });
        d.show();
    }

    // ---- menu ----

    private void menu() {
        String[] items = {
                "Открыть программу / диск / картридж…",
                "Мои программы…",
                "Поймай звезду (встроенная игра)",
                "Atari BASIC",
                "Сохранить состояние",
                "Загрузить состояние",
                "Сброс Atari",
                "Память…",
                "Настройки…",
                "О программе и лицензии",
        };
        show(new AlertDialog.Builder(this).setTitle("Atari 65XE").setItems(items, (d, n) -> {
            switch (n) {
                case 0: pick(PICK_GAME); break;
                case 1: library(); break;
                case 2: runner.post(() -> open(new File(root, "demo.xex").getAbsolutePath(), "Поймай звезду", false)); break;
                case 3: runner.post(() -> open("", "Atari BASIC", false)); break;
                case 4:
                    runner.post(() -> {
                        runner.save("quick");
                        runOnUiThread(() -> toast("Сохранено: " + currentTitle));
                    });
                    break;
                case 5:
                    runner.post(() -> {
                        runner.load("quick");
                        runOnUiThread(() -> toast("Состояние восстановлено"));
                    });
                    break;
                case 6: runner.post(runner::reset); break;
                case 7: memory(0); break;
                case 8: settings(); break;
                default: about();
            }
        }));
    }

    private void settings() {
        boolean gameBasic = prefs.getBoolean("gameBasic", false);
        String[] items = {
                runner.sound ? "Звук: включён" : "Звук: выключен",
                gameBasic ? "BASIC при загрузке игр: включён" : "BASIC при загрузке игр: выключен",
                "Установить своё OS ROM (16 КБ)…",
                "Установить своё BASIC ROM (8 КБ)…",
                "Вернуть свободные OS/BASIC (Altirra)",
        };
        show(new AlertDialog.Builder(this).setTitle("Настройки").setItems(items, (d, n) -> {
            switch (n) {
                case 0:
                    runner.sound = !runner.sound;
                    prefs.edit().putBoolean("sound", runner.sound).apply();
                    break;
                case 1:
                    prefs.edit().putBoolean("gameBasic", !gameBasic).apply();
                    runner.post(() -> open(current, currentTitle, false));
                    break;
                case 2: pick(PICK_OS); break;
                case 3: pick(PICK_BASIC); break;
                default:
                    runner.post(() -> {
                        for (String f : new String[] {"ATARIXL.ROM", "ATARIBAS.ROM", "os.rom", "basic.rom"}) {
                            File file = new File(root, f);
                            if (file.exists() && !file.delete()) throw new IOException("Не удалось сбросить ПЗУ");
                        }
                        open(current, currentTitle, false);
                    });
            }
        }));
    }

    /** Runs on the emulation thread. BASIC is on for "" (BASIC itself) or if the setting asks for it. */
    private void open(String path, String name, boolean auto) throws Exception {
        runner.open(path, path.isEmpty() || prefs.getBoolean("gameBasic", false), auto);
        current = path;
        currentTitle = name;
        prefs.edit().putString("path", path).putString("title", name).apply();
        runOnUiThread(() -> {
            if (!isDestroyed()) title.setText(name + " · PAL");
        });
    }

    private void library() {
        File[] files = new File(root, "games").listFiles((dir, name) -> !name.endsWith(".tmp"));
        if (files == null || files.length == 0) {
            toast("Пока нет программ. Откройте свой файл или встроенную игру.");
            return;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) names[i] = prefs.getString("name:" + files[i].getName(), files[i].getName());
        show(new AlertDialog.Builder(this).setTitle("Мои программы").setItems(names,
                (d, i) -> runner.post(() -> open(files[i].getAbsolutePath(), names[i], true))));
    }

    /** Hex dump of one 256-byte page of the running machine, with ◀ ▶ to move. */
    private void memory(int page) {
        runner.post(() -> {
            byte[] ram = NativeAtari.memory();
            if (ram == null) return;
            StringBuilder dump = new StringBuilder();
            for (int i = 0; i < 256; i += 16) {
                dump.append(String.format(Locale.US, "%04X ", page + i));
                for (int j = 0; j < 16; j++) dump.append(String.format(Locale.US, "%02X ", ram[(page + i + j) & 0xffff] & 255));
                dump.append('\n');
            }
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                TextView text = new TextView(this);
                text.setTypeface(Typeface.MONOSPACE);
                text.setTextSize(11);
                text.setText(dump);
                ScrollView scroll = new ScrollView(this);
                scroll.addView(text);
                show(new AlertDialog.Builder(this)
                        .setTitle(String.format(Locale.US, "Память $%04X", page))
                        .setView(scroll)
                        .setNegativeButton("◀", (d, w) -> memory((page - 256) & 0xff00))
                        .setPositiveButton("▶", (d, w) -> memory((page + 256) & 0xff00))
                        .setNeutralButton("Закрыть", null));
            });
        });
    }

    private void about() {
        show(new AlertDialog.Builder(this).setTitle("Atari 65XE")
                .setMessage("Эмулятор Atari 65XE / 800XL (PAL, 64 КБ): видео ANTIC/GTIA, звук POKEY, "
                        + "файлы XEX, ATR, CAR, CAS; экранные джойстик и клавиатура.\n\n"
                        + "Игровое ядро: Atari800 7.0.0 (libretro), GPL-2.0-or-later. "
                        + "Свободные замены Atari OS и BASIC — Altirra © Avery Lee.\n\n"
                        + "«Поймай звезду» — демонстрационная игра, написанная для этого приложения, CC0.\n\n"
                        + "Исходники: github.com/AlexSpec26/specialist-emu, папка atari; "
                        + "архив исходников приложен к каждому релизу.")
                .setPositiveButton("Лицензии", (d, w) -> showLicenses())
                .setNegativeButton("OK", null));
    }

    private void showLicenses() {
        runner.post(() -> {
            String text;
            try (InputStream in = getAssets().open("licenses.txt"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
                text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            runOnUiThread(() -> {
                if (!isDestroyed()) show(new AlertDialog.Builder(this).setTitle("Licenses").setMessage(text).setPositiveButton("OK", null));
            });
        });
    }

    // ---- importing files ----

    private void pick(int type) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, type);
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (RuntimeException ignored) {
        }
        return name == null ? "game.xex" : name;
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = displayName(uri);
        runner.post(() -> {
            if (request == PICK_OS || request == PICK_BASIC) importRom(uri, request == PICK_OS);
            else if (request == PICK_GAME) importGame(uri, name);
        });
    }

    /** Emulation thread. Stores an exact-size OS or BASIC image and restarts the current program. */
    private void importRom(Uri uri, boolean os) throws Exception {
        int size = os ? OS_SIZE : BASIC_SIZE;
        File temp = new File(root, "rom-import.tmp");
        copy(getContentResolver().openInputStream(uri), temp, size);
        if (temp.length() != size) {
            temp.delete();
            throw new IOException("Нужно ровно " + size + " байт");
        }
        if (!temp.renameTo(new File(root, os ? "ATARIXL.ROM" : "ATARIBAS.ROM"))) throw new IOException("Не удалось сохранить ПЗУ");
        open(current, currentTitle, false);
    }

    /**
     * Emulation thread. Copies the file into games/ named by its SHA-256 (so the same program is
     * stored once and its save states survive renaming), then starts it.
     */
    private void importGame(Uri uri, String name) throws Exception {
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!GAME_TYPES.contains(ext)) throw new IOException("Откройте XEX, ATR, CAR или CAS (также XFD/ATX/DCM)");
        File dir = new File(root, "games");
        dir.mkdirs();
        File temp = new File(dir, "import.tmp");
        copy(getContentResolver().openInputStream(uri), temp, AtariRunner.MAX_CONTENT);
        String id = AtariRunner.digest(AtariRunner.read(temp, AtariRunner.MAX_CONTENT));
        File game = new File(dir, id + "." + ext);
        if (game.exists()) temp.delete();
        else if (!temp.renameTo(game)) throw new IOException("Не удалось сохранить программу");
        open(game.getAbsolutePath(), name, false);
        prefs.edit().putString("name:" + game.getName(), name).apply();
    }

    private static void copy(InputStream source, File file, int limit) throws IOException {
        if (source == null) throw new IOException("Не удалось открыть файл");
        try (InputStream in = source; OutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > limit) throw new IOException("Слишком большой файл (максимум " + limit + " байт)");
                out.write(buffer, 0, n);
            }
        }
    }

    // ---- lifecycle ----

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (runner != null) updatePause();
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (view != null) view.releaseAll();
        physical.clear();
        if (runner != null) {
            runner.post(() -> {
                try {
                    runner.save("auto");
                } catch (IOException ignored) {
                    // nothing running yet
                }
            });
            updatePause();
        }
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (!focus && view != null) {
            view.releaseAll();
            physical.clear();
        }
    }

    @Override
    protected void onDestroy() {
        if (runner != null) runner.quit();
        super.onDestroy();
    }

    // ---- physical gamepad and keyboard ----

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        boolean joystick = (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        if (view != null && dialogs == 0 && joystick && event.getAction() == MotionEvent.ACTION_MOVE) {
            float x = event.getAxisValue(MotionEvent.AXIS_X), y = event.getAxisValue(MotionEvent.AXIS_Y);
            float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X), hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            view.analog(Math.abs(hatX) > .3f ? hatX : x, Math.abs(hatY) > .3f ? hatY : y);
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    /** Arrows/D-pad → joystick, A/B → FIRE, F1/F2/F3 → START/SELECT/OPTION, other keys → Atari keys. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int action = e.getAction();
        if (view == null || dialogs > 0 || (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP)) {
            return super.dispatchKeyEvent(e);
        }
        boolean down = action == KeyEvent.ACTION_DOWN;
        int code = e.getKeyCode();
        int bit = joypadBit(code);
        if (bit >= 0) {
            view.physicalButton(bit, down);
            return true;
        }
        int key = atariKey(code, e);
        if (key < 0) return super.dispatchKeyEvent(e);
        if (down) {
            physical.put(code, key);
        } else {
            key = physical.get(code, key);
            physical.delete(code);
        }
        view.physicalKey(key, down);
        return true;
    }

    private static int joypadBit(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return PlayView.BIT_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return PlayView.BIT_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return PlayView.BIT_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return PlayView.BIT_RIGHT;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_B: return PlayView.BIT_FIRE;
            case KeyEvent.KEYCODE_F1: return PlayView.BIT_START;
            case KeyEvent.KEYCODE_F2: return PlayView.BIT_SELECT;
            case KeyEvent.KEYCODE_F3: return PlayView.BIT_OPTION;
            default: return -1;
        }
    }

    /** libretro key code for an Android key, or -1. */
    private static int atariKey(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_ENTER: return PlayView.KEY_RETURN;
            case KeyEvent.KEYCODE_DEL: return PlayView.KEY_BACKSPACE;
            case KeyEvent.KEYCODE_ESCAPE: return PlayView.KEY_ESCAPE;
            case KeyEvent.KEYCODE_TAB: return PlayView.KEY_TAB;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return PlayView.KEY_SHIFT;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT: return PlayView.KEY_CTRL;
            default:
                if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) return 'a' + code - KeyEvent.KEYCODE_A;
                if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) return '0' + code - KeyEvent.KEYCODE_0;
                int c = e.getUnicodeChar(0);
                return c > 0 && c < 128 ? c : -1;
        }
    }
}
