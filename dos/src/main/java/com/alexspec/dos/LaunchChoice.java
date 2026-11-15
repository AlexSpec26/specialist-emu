package com.alexspec.dos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which program a game starts and which of its folders is the game's root (the folder that
 * appears as C:\NAME). Chosen once when the game is added (or on its first start), changed in the
 * game's menu; then the game starts straight away, without DOSBox's start menu.
 *
 * Kept in the "dos" preferences per game: "root:NAME" — the root folder relative to the folder the
 * game was added as ("" = that folder itself), "exe:NAME" — the program relative to the root
 * ("" = no program, DOSBox's menu on every start). The game starts through AUTOBOOT.DBP, which
 * DOSBox Pure reads from the root of C: — that root is the app's own mount folder
 * (files/mounts/NAME), so the game's files stay untouched.
 */
final class LaunchChoice {
    private LaunchChoice() {
    }

    static final String KEY_ROOT = "root:", KEY_EXE = "exe:";

    static boolean isProgram(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".exe") || n.endsWith(".com") || n.endsWith(".bat");
    }

    /** the programs in the folder and its subfolders, as paths relative to it ("A/B.EXE") */
    static List<String> programs(File top) {
        List<String> out = new ArrayList<>();
        walk(top, "", out, 0);
        // the game itself first: setup, install and the like at the end
        out.sort((a, b) -> {
            int ua = utility(a) ? 1 : 0, ub = utility(b) ? 1 : 0;
            if (ua != ub) return ua - ub;
            int da = depth(a), db = depth(b);
            if (da != db) return da - db;
            return a.compareToIgnoreCase(b);
        });
        return out;
    }

    private static void walk(File dir, String rel, List<String> out, int depth) {
        File[] in = dir.listFiles();
        if (in == null || depth > 8) return;
        for (File f : in) {
            if (f.isDirectory()) walk(f, rel + f.getName() + "/", out, depth + 1);
            else if (isProgram(f.getName())) out.add(rel + f.getName());
        }
    }

    private static int depth(String rel) {
        int n = 0;
        for (char c : rel.toCharArray()) if (c == '/') n++;
        return n;
    }

    static boolean utility(String rel) {
        String n = new File(rel).getName().toUpperCase(Locale.ROOT);
        return n.startsWith("SETUP") || n.startsWith("INSTALL") || n.startsWith("CONFIG") || n.startsWith("SETSOUND")
                || n.startsWith("SOUND") || n.startsWith("UNINST") || n.startsWith("README") || n.startsWith("CATALOG")
                || n.startsWith("ORDER") || n.startsWith("PKUNZIP") || n.startsWith("UNZIP");
    }

    /** the folders a program can be started from as the game's root: its own folder and up to top */
    static List<String> roots(String exeRel) {
        List<String> out = new ArrayList<>();
        String p = exeRel;
        int s;
        while ((s = p.lastIndexOf('/')) >= 0) {
            p = p.substring(0, s);
            out.add(p);
        }
        out.add("");
        return out;
    }

    /** the root by default: the folder auto-detection would pick, if it holds the program */
    static String defaultRoot(File top, String exeRel) {
        String auto = rel(top, GameFiles.gameDir(top));
        if (auto != null && (auto.isEmpty() || exeRel.startsWith(auto + "/"))) return auto;
        int s = exeRel.lastIndexOf('/');
        return s < 0 ? "" : exeRel.substring(0, s);
    }

    private static String rel(File top, File f) {
        String a = top.getAbsolutePath(), b = f.getAbsolutePath();
        if (b.equals(a)) return "";
        if (b.startsWith(a + "/")) return b.substring(a.length() + 1);
        return null;
    }

    static boolean chosen(SharedPreferences prefs, String name) {
        return prefs.contains(KEY_EXE + name);
    }

    /** the game's root folder: chosen, or found automatically */
    static File root(SharedPreferences prefs, String name, File top) {
        String r = prefs.getString(KEY_ROOT + name, null);
        if (r != null) {
            File f = r.isEmpty() ? top : new File(top, r);
            if (f.isDirectory()) return f;
        }
        return GameFiles.gameDir(top);
    }

    /** the program to start, relative to the root ("" or null: DOSBox's menu) */
    static String exe(SharedPreferences prefs, String name, File top) {
        String e = prefs.getString(KEY_EXE + name, null);
        if (e != null) return e;
        // not chosen (a game added before): the only program of the root starts by itself
        List<String> all = programs(root(prefs, name, top));
        return all.size() == 1 ? all.get(0) : null;
    }

    /**
     * Writes or removes AUTOBOOT.DBP in the mount folder (the root of C:), so that DOSBox starts
     * C:\dosDir\exe at once. exe null or "": its start menu.
     */
    static void writeAutoboot(File mountFolder, String dosDir, String exe) {
        File f = new File(mountFolder, "AUTOBOOT.DBP");
        if (exe == null || exe.isEmpty()) {
            f.delete();
            return;
        }
        String line = "C:\\" + dosDir + "\\" + exe.replace('/', '\\').toUpperCase(Locale.ROOT);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(line.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            android.util.Log.e("DOS", "no AUTOBOOT.DBP", e);
        }
    }

    /**
     * Asks which program starts the game, then (if there is a choice) which folder is the game's
     * root; saves both and runs done. With no programs at all, done runs at once.
     */
    static void ask(Activity a, SharedPreferences prefs, String name, File top, Runnable done) {
        List<String> progs = programs(top);
        if (progs.isEmpty()) {
            done.run();
            return;
        }
        String cur = prefs.getString(KEY_EXE + name, null);
        String curRoot = prefs.getString(KEY_ROOT + name, null);
        String curFull = cur == null || cur.isEmpty() ? null : (curRoot == null || curRoot.isEmpty() ? cur : curRoot + "/" + cur);
        String[] items = new String[progs.size() + 1];
        int sel = 0;
        for (int i = 0; i < progs.size(); i++) {
            items[i] = progs.get(i).replace('/', '\\') + (utility(progs.get(i)) ? "   (настройка?)" : "");
            if (progs.get(i).equals(curFull)) sel = i;
        }
        items[progs.size()] = "Меню DOSBox при каждом запуске";
        if (cur != null && cur.isEmpty()) sel = progs.size();
        int[] pick = {sel};
        new AlertDialog.Builder(a)
                .setTitle("Чем запускать «" + name + "»")
                .setSingleChoiceItems(items, sel, (d, w) -> pick[0] = w)
                .setPositiveButton("Дальше", (d, w) -> {
                    if (pick[0] == progs.size()) {
                        prefs.edit().putString(KEY_EXE + name, "").remove(KEY_ROOT + name).commit();
                        done.run();
                    } else {
                        askRoot(a, prefs, name, top, progs.get(pick[0]), done);
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static void askRoot(Activity a, SharedPreferences prefs, String name, File top, String exeRel, Runnable done) {
        List<String> roots = roots(exeRel);
        String def = defaultRoot(top, exeRel);
        if (roots.size() == 1) {
            String old = prefs.getString(KEY_ROOT + name, null);
            save(prefs, name, "", exeRel, old != null && !old.isEmpty());
            done.run();
            return;
        }
        String[] items = new String[roots.size()];
        int sel = Math.max(0, roots.indexOf(def));
        for (int i = 0; i < roots.size(); i++) {
            String r = roots.get(i);
            String folder = r.isEmpty() ? top.getName() : new File(r).getName();
            String inside = r.isEmpty() ? exeRel : exeRel.substring(r.length() + 1);
            items[i] = "C:\\" + GameFiles.dosName(folder) + "\\" + inside.replace('/', '\\').toUpperCase(Locale.ROOT)
                    + "\n    папка «" + (r.isEmpty() ? top.getName() : r) + "»" + (r.equals(def) ? " — обычно так" : "");
        }
        int[] pick = {sel};
        new AlertDialog.Builder(a)
                .setTitle("Корневая папка игры (станет C:\\ИМЯ)")
                .setSingleChoiceItems(items, sel, (d, w) -> pick[0] = w)
                .setPositiveButton("Готово", (d, w) -> {
                    String r = roots.get(pick[0]);
                    String old = prefs.getString(KEY_ROOT + name, null);
                    save(prefs, name, r, r.isEmpty() ? exeRel : exeRel.substring(r.length() + 1), old != null && !old.equals(r));
                    done.run();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static void save(SharedPreferences prefs, String name, String root, String exe, boolean rootChanged) {
        SharedPreferences.Editor e = prefs.edit().putString(KEY_ROOT + name, root).putString(KEY_EXE + name, exe);
        if (rootChanged) e.remove("dosname:" + name); // the DOS name of the old root does not fit the new one
        e.commit(); // the game runs in another process, which reads the file
    }
}
