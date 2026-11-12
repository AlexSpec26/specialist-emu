package com.alexspec.dos;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * How a game's files reach DOS. The game's own files are never changed by the app (they are a
 * piece of history): only where they appear is arranged.
 *
 * A game folder is shown as C:\NAME, like on a hard disk of 1991 — not as the root of C:. Many
 * games open their data by an absolute path ("\RETAL\RETAL.00" in Retaliator) and quietly quit
 * when it is not there. The app makes an empty folder per game (files/mounts/&lt;game&gt;) with one
 * link NAME → the game folder, and DOSBox mounts that folder as C:. NAME is the folder's name
 * in 8.3 capitals, or the one set for the game in the menu.
 */
final class GameFiles {
    private GameFiles() {
    }

    private static boolean isProgram(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".exe") || n.endsWith(".com") || n.endsWith(".bat");
    }

    /**
     * The game's own folder: where its programs lie. "X/X/…" from an archive or a copied folder:
     * while a folder has no programs and exactly one subfolder, the game is in that subfolder.
     */
    static File gameDir(File dir) {
        for (int depth = 0; depth < 8; depth++) {
            File[] in = dir.listFiles();
            if (in == null) return dir;
            File onlyDir = null;
            int dirs = 0;
            for (File f : in) {
                if (f.isFile() && isProgram(f.getName())) return dir;
                if (f.isDirectory()) {
                    dirs++;
                    onlyDir = f;
                }
            }
            if (dirs != 1) return dir;
            dir = onlyDir;
        }
        return dir;
    }

    /** a DOS directory name: 8 characters, capitals, only what DOS allows; "GAME" if nothing is left */
    static String dosName(String host) {
        String s = host.toUpperCase(Locale.ROOT);
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (b.length() == 8) break;
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "_-!#$%&'()@^{}~".indexOf(c) >= 0) b.append(c);
        }
        return b.length() > 0 ? b.toString() : "GAME";
    }

    /**
     * Makes files/mounts/&lt;key&gt; hold only the link &lt;dosName&gt; → gameDir; returns that folder (the
     * content for DOSBox), or null if links cannot be made here.
     */
    static File mountFolder(File filesDir, String key, File gameDir, String dosName) {
        File wrap = new File(new File(filesDir, "mounts"), key);
        wrap.mkdirs();
        File[] old = wrap.listFiles();
        if (old != null) {
            for (File f : old) {
                // only links live here; never walk into a link (that is the game itself)
                if (Files.isSymbolicLink(f.toPath()) || f.isFile()) f.delete();
                else return null;
            }
        }
        try {
            android.system.Os.symlink(gameDir.getAbsolutePath(), new File(wrap, dosName).getAbsolutePath());
            return wrap;
        } catch (Exception e) {
            android.util.Log.e("DOS", "no link for " + gameDir, e);
            return null;
        }
    }

    /**
     * Unpacks a zip into dst, keeping the files' dates; entries outside dst are skipped.
     * Returns the number of files.
     */
    static int unzip(File zip, File dst) throws IOException {
        int n = 0;
        java.util.Map<File, Long> dirTimes = new java.util.HashMap<>();
        String root = dst.getCanonicalPath() + File.separator;
        try (ZipFile z = new ZipFile(zip)) {
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName().replace('\\', '/');
                File out = new File(dst, name);
                if (!out.getCanonicalPath().startsWith(root)) continue;
                if (e.isDirectory()) {
                    out.mkdirs();
                    if (e.getTime() > 0) dirTimes.put(out, e.getTime());
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (InputStream in = z.getInputStream(e); OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[1 << 16];
                    int r;
                    while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
                }
                if (e.getTime() > 0) out.setLastModified(e.getTime());
                n++;
            }
        }
        for (java.util.Map.Entry<File, Long> d : dirTimes.entrySet()) d.getKey().setLastModified(d.getValue());
        return n;
    }

    // ------------------------------------------------------------------ the museum

    /** files/museum: an untouched copy of every game as it was added (read-only files) */
    static File museum(File filesDir) {
        return new File(filesDir, "museum");
    }

    /**
     * Copies a file or a folder with everything in it, keeping the dates (folders get theirs
     * after their contents). Links are not followed. Returns false if something failed.
     */
    static boolean copyTree(File from, File to, boolean readOnly) {
        if (Files.isSymbolicLink(from.toPath())) return true;
        if (from.isDirectory()) {
            to.mkdirs();
            boolean ok = true;
            File[] in = from.listFiles();
            if (in != null) for (File f : in) ok &= copyTree(f, new File(to, f.getName()), readOnly);
            to.setLastModified(from.lastModified());
            return ok;
        }
        File tmp = new File(to.getPath() + ".tmp");
        try (InputStream in = new java.io.FileInputStream(from); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        if (to.exists()) {
            to.setWritable(true);
            to.delete();
        }
        if (!tmp.renameTo(to)) return false;
        to.setLastModified(from.lastModified());
        if (readOnly) to.setWritable(false, false);
        return true;
    }

    static void deleteTree(File f) {
        if (!Files.isSymbolicLink(f.toPath())) {
            File[] in = f.listFiles();
            if (in != null) for (File x : in) deleteTree(x);
        }
        f.setWritable(true);
        f.delete();
    }

    /** puts the freshly added game (its working copy) into the museum */
    static boolean toMuseum(File filesDir, File game) {
        File m = new File(museum(filesDir), game.getName());
        deleteTree(m);
        museum(filesDir).mkdirs();
        return copyTree(game, m, true);
    }

    static boolean inMuseum(File filesDir, File game) {
        return new File(museum(filesDir), game.getName()).exists();
    }

    /** the working copy again as in the museum: everything the game wrote is gone */
    static boolean restoreFromMuseum(File filesDir, File game) {
        File m = new File(museum(filesDir), game.getName());
        if (!m.exists()) return false;
        deleteTree(game);
        boolean ok = copyTree(m, game, false);
        // the copy must be writable again (the museum's files are read-only)
        makeWritable(game);
        return ok;
    }

    private static void makeWritable(File f) {
        f.setWritable(true);
        File[] in = f.listFiles();
        if (in != null) for (File x : in) makeWritable(x);
    }

    static boolean isZip(File f) {
        return f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".zip");
    }
}
