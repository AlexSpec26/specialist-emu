package com.alexspec.dos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One DOS game. Runs in its own process (":game"): the DOSBox core starts only once per
 * process, so another game means a new process — leaving this screen ends it.
 *
 * Saves: quick save / load (one slot per game), "save as" / "load from file" through the
 * system file picker, and an automatic save when leaving, offered on the next start.
 */
public final class GameActivity extends Activity implements GameView.Host, EmuThread.Listener {
    static final String EXTRA_PATH = "path", EXTRA_NAME = "name";
    private static final int REQ_SAVE_AS = 1, REQ_LOAD_FILE = 2;

    /** CPU speed presets: DOSBox cycles per millisecond and the name of the machine */
    static final String[] CPU_VALUES = {"315", "1320", "2750", "4720", "7800", "13400", "26800", "auto"};
    static final String[] CPU_NAMES = {"8086, 4,77 МГц (IBM PC)", "286, 6 МГц (IBM AT)", "286, 12,5 МГц",
            "386SX, 20 МГц", "386DX, 33 МГц", "486DX, 33 МГц", "486DX2, 66 МГц", "Авто (сколько потянет телефон)"};
    static final String DEFAULT_CPU = "2750";
    private static final int[] SLOW = {1, 2, 3, 4, 8};
    private static final String[] SLOW_LABELS = {"1×", "½", "⅓", "¼", "⅛"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private GameView view;
    private EmuThread emu;
    private String name;
    private File states;
    private boolean userPaused;
    private int slowIndex;
    private volatile boolean opened;
    /** the automatic save is loaded as soon as the game runs (the core loads only then) */
    private volatile byte[] pendingAuto;
    private volatile long pendingUntil;
    private double lastAspect;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("dos", MODE_PRIVATE);
        String path = getIntent().getStringExtra(EXTRA_PATH);
        name = getIntent().getStringExtra(EXTRA_NAME);
        if (path == null) path = "";
        if (name == null || name.isEmpty()) name = "DOS";
        states = new File(getFilesDir(), "states");
        states.mkdirs();
        File system = new File(getFilesDir(), "system"), saves = new File(getFilesDir(), "saves");
        system.mkdirs();
        saves.mkdirs();

        view = new GameView(this, this);
        view.title = name;
        view.controlsMode = prefs.getInt("controls:" + name, GameView.CONTROLS_PAD);
        setContentView(view);
        hideSystemBars();

        NativeDos.option("dosbox_pure_cycles", prefs.getString("cpu:" + name, DEFAULT_CPU));
        emu = new EmuThread(system.getAbsolutePath(), saves.getAbsolutePath(), path, this);
        emu.sound = prefs.getBoolean("sound", true);
        view.attach(emu);
        emu.start();
        offerContinue();
    }

    // ------------------------------------------------------------------ emulation thread callbacks

    @Override
    public void onOpened(boolean ok) {
        opened = ok;
        if (!ok) {
            ui.post(() -> new AlertDialog.Builder(this)
                    .setTitle("Не запускается")
                    .setMessage("DOSBox не смог открыть «" + name + "». Подходят архивы .zip, программы .exe/.com/.bat, "
                            + "образы дисков .img/.ima/.iso/.cue.")
                    .setPositiveButton("К списку игр", (d, w) -> finish())
                    .setCancelable(false)
                    .show());
        }
    }

    private int frameCount;

    @Override
    public void onFrame() {
        frameCount++;
        byte[] auto = pendingAuto;
        // every half second: try to load the automatic save (works once the game runs)
        if (auto != null && frameCount % 35 == 0) {
            if (NativeDos.load(auto)) {
                pendingAuto = null;
                ui.post(() -> view.showNote("Продолжено с места выхода"));
            } else if (System.currentTimeMillis() > pendingUntil) {
                pendingAuto = null;
            }
        }
        double a = emu.aspect;
        if (a != lastAspect) {
            lastAspect = a;
            ui.post(view::relayout);
        }
        view.postInvalidate();
    }

    // ------------------------------------------------------------------ GameView.Host

    @Override
    public void onMenu() {
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        items.add("💾  Быстрое сохранение");
        acts.add(this::onQuickSave);
        items.add("📂  Быстрая загрузка");
        acts.add(this::onQuickLoad);
        items.add("💾  Сохранить как…");
        acts.add(this::saveAs);
        items.add("📂  Загрузить из файла…");
        acts.add(this::loadFromFile);
        items.add("⏱  Скорость: " + speedText(slowIndex) + "…");
        acts.add(this::chooseSpeed);
        items.add("🖥  Процессор: " + cpuName(prefs.getString("cpu:" + name, DEFAULT_CPU)) + "…");
        acts.add(this::chooseCpu);
        items.add(view.controlsMode == GameView.CONTROLS_PAD ? "⌨  Управление: джойстик → клавиатура" : "🎮  Управление: клавиатура → джойстик");
        acts.add(this::onControlsToggle);
        items.add(emu.sound ? "🔇  Выключить звук" : "🔊  Включить звук");
        acts.add(() -> {
            emu.sound = !emu.sound;
            prefs.edit().putBoolean("sound", emu.sound).apply();
        });
        items.add("↺  Начать игру заново");
        acts.add(this::restart);
        items.add("❓  Справка");
        acts.add(this::help);
        items.add("◀  К списку игр");
        acts.add(this::finish);
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(items.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .show();
    }

    @Override
    public void onPauseToggle() {
        userPaused = !userPaused;
        applyPause();
    }

    private void applyPause() {
        boolean p = userPaused;
        view.paused = p;
        if (p) view.releaseAll();
        emu.setPaused(p);
        view.relayout();
    }

    @Override
    public void onRewind(double seconds) {
        emu.post(() -> {
            double went = NativeDos.rewind(seconds);
            if (went > 0 && emu.isPaused()) emu.refreshPicture();
            String s = went > 0 ? String.format(Locale.ROOT, "⟲ назад на %.1f с", went).replace('.', ',')
                    : "Назад некуда: история ещё не накопилась";
            ui.post(() -> view.showNote(s));
        });
    }

    @Override
    public void onSpeed() {
        setSpeed((slowIndex + 1) % SLOW.length);
    }

    private void setSpeed(int i) {
        slowIndex = i;
        emu.slow = SLOW[i];
        view.speedLabel = SLOW_LABELS[i];
        view.showNote(i == 0 ? "Скорость реальная" : "Медленнее в " + SLOW[i] + " раз" + (SLOW[i] < 5 && SLOW[i] > 1 ? "а" : ""));
        view.relayout();
    }

    private static String speedText(int i) {
        return i == 0 ? "реальная" : "в " + SLOW[i] + " раз" + (SLOW[i] < 5 && SLOW[i] > 1 ? "а" : "") + " медленнее";
    }

    private void chooseSpeed() {
        String[] names = new String[SLOW.length];
        for (int i = 0; i < SLOW.length; i++) names[i] = SLOW_LABELS[i] + "  —  " + speedText(i);
        new AlertDialog.Builder(this)
                .setTitle("Скорость")
                .setSingleChoiceItems(names, slowIndex, (d, w) -> {
                    setSpeed(w);
                    d.dismiss();
                })
                .show();
    }

    private static String cpuName(String value) {
        for (int i = 0; i < CPU_VALUES.length; i++) if (CPU_VALUES[i].equals(value)) return CPU_NAMES[i];
        return value;
    }

    private void chooseCpu() {
        String cur = prefs.getString("cpu:" + name, DEFAULT_CPU);
        int sel = 2;
        for (int i = 0; i < CPU_VALUES.length; i++) if (CPU_VALUES[i].equals(cur)) sel = i;
        new AlertDialog.Builder(this)
                .setTitle("Процессор")
                .setSingleChoiceItems(CPU_NAMES, sel, (d, w) -> {
                    String v = CPU_VALUES[w];
                    prefs.edit().putString("cpu:" + name, v).apply();
                    emu.post(() -> NativeDos.option("dosbox_pure_cycles", v));
                    view.showNote(CPU_NAMES[w]);
                    d.dismiss();
                })
                .show();
    }

    @Override
    public void onQuickSave() {
        File f = new File(states, safe(name) + ".quick");
        emu.post(() -> {
            byte[] s = NativeDos.save();
            boolean ok = s != null && write(f, s);
            ui.post(() -> view.showNote(ok ? "💾 Сохранено" : "Не сохранилось: игра ещё не запущена"));
        });
    }

    @Override
    public void onQuickLoad() {
        File f = new File(states, safe(name) + ".quick");
        if (!f.exists()) {
            view.showNote("Быстрого сохранения ещё нет");
            return;
        }
        loadState(read(f), "📂 Загружено");
    }

    private void loadState(byte[] s, String done) {
        if (s == null) {
            view.showNote("Файл не читается");
            return;
        }
        emu.post(() -> {
            boolean ok = NativeDos.load(s);
            if (ok && emu.isPaused()) emu.refreshPicture();
            ui.post(() -> {
                if (ok) view.showNote(done);
                else new AlertDialog.Builder(this)
                        .setTitle("Не загрузилось")
                        .setMessage("Сохранение загружается, когда игра уже запущена (не в меню выбора программы) "
                                + "и с тем же процессором и памятью, с которыми оно сделано.")
                        .setPositiveButton("OK", null)
                        .show();
            });
        });
    }

    private void saveAs() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, safe(name) + ".dosstate");
        try {
            startActivityForResult(i, REQ_SAVE_AS);
        } catch (Exception e) {
            toast("Нет приложения для выбора файла");
        }
    }

    private void loadFromFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_LOAD_FILE);
        } catch (Exception e) {
            toast("Нет приложения для выбора файла");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_SAVE_AS) {
            emu.post(() -> {
                byte[] s = NativeDos.save();
                boolean ok = false;
                if (s != null) {
                    try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                        if (out != null) {
                            out.write(s);
                            ok = true;
                        }
                    } catch (IOException | RuntimeException ignored) {
                    }
                }
                boolean done = ok;
                ui.post(() -> view.showNote(done ? "💾 Сохранено в файл" : "Не сохранилось"));
            });
        } else if (req == REQ_LOAD_FILE) {
            byte[] s = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in != null) s = readAll(in);
            } catch (IOException | RuntimeException ignored) {
            }
            loadState(s, "📂 Загружено из файла");
        }
    }

    @Override
    public void onControlsToggle() {
        boolean land = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        view.releaseAll();
        if (land) {
            view.keyboardShown = !view.keyboardShown;
        } else {
            view.controlsMode = view.controlsMode == GameView.CONTROLS_PAD ? GameView.CONTROLS_KEYBOARD : GameView.CONTROLS_PAD;
            prefs.edit().putInt("controls:" + name, view.controlsMode).apply();
        }
        view.relayout();
    }

    @Override
    public void onKey(int code, boolean down) {
        emu.post(() -> NativeDos.key(code, down));
    }

    @Override
    public void onPointer(int x, int y, boolean pressed, int count) {
        emu.post(() -> NativeDos.pointer(x, y, pressed, count));
    }

    // ------------------------------------------------------------------ hardware keyboard

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int code = PcKeys.fromAndroid(ev.getKeyCode());
        if (code != 0 && (ev.getAction() == KeyEvent.ACTION_DOWN || ev.getAction() == KeyEvent.ACTION_UP)) {
            if (ev.getRepeatCount() == 0) onKey(code, ev.getAction() == KeyEvent.ACTION_DOWN);
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    // ------------------------------------------------------------------ saves on leaving

    private void offerContinue() {
        File auto = new File(states, safe(name) + ".auto");
        if (!auto.exists()) return;
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setMessage("Продолжить с места, где вы вышли?")
                .setPositiveButton("Продолжить", (d, w) -> {
                    pendingAuto = read(auto);
                    pendingUntil = System.currentTimeMillis() + 120_000;
                    view.showNote("Если появится меню — запустите игру, продолжу с места выхода");
                })
                .setNegativeButton("Сначала", null)
                .show();
    }

    private void autosave() {
        if (!opened) return;
        File f = new File(states, safe(name) + ".auto");
        emu.post(() -> {
            byte[] s = NativeDos.save();
            if (s != null) write(f, s);
        });
    }

    private void restart() {
        new File(states, safe(name) + ".auto").delete();
        Intent i = new Intent(this, LibraryActivity.class);
        i.putExtra(LibraryActivity.EXTRA_RELAUNCH_PATH, getIntent().getStringExtra(EXTRA_PATH));
        i.putExtra(LibraryActivity.EXTRA_RELAUNCH_NAME, name);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        restarting = true;
        startActivity(i);
        finish();
    }

    private boolean restarting;

    @Override
    protected void onPause() {
        super.onPause();
        if (!restarting) autosave();
        view.releaseAll();
        emu.setPaused(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        emu.setPaused(userPaused);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            // the save posted by onPause must reach the disk before the process goes
            Object done = new Object();
            emu.post(() -> {
                synchronized (done) {
                    done.notifyAll();
                }
            });
            emu.setPaused(false);
            synchronized (done) {
                try {
                    done.wait(1500);
                } catch (InterruptedException ignored) {
                }
            }
            emu.quit();
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        view.keyboardShown = false;
        view.releaseAll();
        hideSystemBars();
        view.relayout();
    }

    // ------------------------------------------------------------------ help, files, bars

    private void help() {
        new AlertDialog.Builder(this)
                .setTitle("Как играть")
                .setMessage("Экран игры — это тачпад мыши: ведите пальцем, чтобы двигать курсор, коснитесь — щелчок, "
                        + "касание двумя пальцами — правая кнопка, долгое нажатие — перетаскивание.\n\n"
                        + "Джойстик слева — стрелки, кнопки справа — Пробел, Ctrl, Alt, Enter и Esc: так управляется "
                        + "большинство старых игр. Кнопка ⌨ переключает на полную клавиатуру ПК (несколько клавиш "
                        + "можно держать разными пальцами, например Shift и букву).\n\n"
                        + "⟲ 1 с и ⟲ 5 с — назад во времени на секунду или на пять (история — около 7 секунд, нажимать "
                        + "можно подряд). Скорость — реальная или медленнее в 2, 3, 4, 8 раз; звук при этом тянется "
                        + "ниже.\n\n"
                        + "💾 и 📂 — быстрое сохранение и загрузка, в меню ещё «Сохранить как…» и «Загрузить из файла». "
                        + "При выходе положение сохраняется само, при следующем запуске предлагается продолжить.\n\n"
                        + "Если в архиве несколько программ, DOSBox покажет меню — выберите игру стрелками и Enter. "
                        + "Процессор по умолчанию — 286 на 12,5 МГц; если игре мало, выберите в меню 386 или 486.")
                .setPositiveButton("Понятно", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9А-Яа-яЁё._ -]", "_");
    }

    private static boolean write(File f, byte[] data) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        } catch (IOException e) {
            return false;
        }
        return tmp.renameTo(f);
    }

    private static byte[] read(File f) {
        try {
            return Files.readAllBytes(f.toPath());
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** portrait: Android's buttons stay visible; landscape: the whole screen */
    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        boolean land = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (land) {
                    c.hide(WindowInsets.Type.systemBars());
                } else {
                    c.hide(WindowInsets.Type.statusBars());
                    c.show(WindowInsets.Type.navigationBars());
                }
            }
        } else {
            int f = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            if (land) f |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            getWindow().getDecorView().setSystemUiVisibility(f);
        }
        getWindow().setNavigationBarColor(0xFF000000);
        view.requestApplyInsets();
    }
}
