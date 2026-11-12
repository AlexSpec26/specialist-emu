package com.alexspec.dos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The list of games: files copied into the app (zip archives, programs, disk images). A game
 * starts in its own process (GameActivity); "DOS without a game" opens DOSBox's start menu with
 * an empty drive C.
 */
public final class LibraryActivity extends Activity {
    static final String EXTRA_RELAUNCH_PATH = "relaunchPath", EXTRA_RELAUNCH_NAME = "relaunchName",
            EXTRA_RESTORE = "restoreFromMuseum";
    private static final int REQ_ADD = 1, REQ_ADD_FOLDER = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private File games;
    private ArrayAdapter<String> adapter;
    private final List<File> files = new ArrayList<>();
    private TextView empty;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        games = new File(getFilesDir(), "games");
        games.mkdirs();
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E1014);
        root.setFitsSystemWindows(true);
        int pad = (int) (16 * dp);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("DOS 286");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFE8E8EC);
        root.addView(title);
        TextView sub = new TextView(this);
        sub.setText("Старые игры для ПК: перемотка на 1 и 5 секунд, замедление, сохранения в любой момент.");
        sub.setTextColor(0xFF9AA0AA);
        sub.setPadding(0, (int) (4 * dp), 0, (int) (12 * dp));
        root.addView(sub);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button add = new Button(this);
        add.setText("＋ Добавить игру");
        add.setOnClickListener(v -> chooseAdd());
        Button bare = new Button(this);
        bare.setText("DOS без игры");
        bare.setOnClickListener(v -> launch("", "DOS"));
        row.addView(add, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(bare, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(row);

        empty = new TextView(this);
        empty.setText("Игр пока нет. Нажмите «Добавить игру»: архив .zip (как обычно скачивают старые игры), "
                + "папку с игрой целиком или образ диска .img/.iso. Игре нужны все её файлы, одного .exe "
                + "обычно мало. Игра копируется в приложение дважды: нетронутая копия — в «музей», а в "
                + "рабочую игра пишет свои сохранения; исходные файлы на телефоне не меняются.\n\nДолгое нажатие на игру — удалить её.");
        empty.setTextColor(0xFF9AA0AA);
        empty.setGravity(Gravity.START);
        empty.setPadding(0, (int) (20 * dp), 0, 0);
        root.addView(empty);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<>()) {
            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, convert, parent);
                t.setTextColor(0xFFE8E8EC);
                t.setTextSize(18);
                return t;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> launch(files.get(pos).getAbsolutePath(), title(files.get(pos))));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            confirmDelete(files.get(pos));
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        refresh();
        relaunchFrom(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        relaunchFrom(intent);
    }

    /** "start again" from a game: its process is gone, start a fresh one */
    private void relaunchFrom(Intent i) {
        String path = i.getStringExtra(EXTRA_RELAUNCH_PATH);
        if (path == null) return;
        String name = i.getStringExtra(EXTRA_RELAUNCH_NAME);
        boolean restore = i.getBooleanExtra(EXTRA_RESTORE, false);
        i.removeExtra(EXTRA_RELAUNCH_PATH);
        i.removeExtra(EXTRA_RESTORE);
        ui.postDelayed(() -> {
            if (!restore) {
                launch(path, name);
                return;
            }
            // after the game's process has ended: its working copy back as in the museum
            new Thread(() -> {
                boolean ok = GameFiles.restoreFromMuseum(getFilesDir(), new File(path));
                ui.post(() -> {
                    Toast.makeText(this, ok ? "«" + name + "» снова как при добавлении" : "Не получилось вернуть файлы",
                            Toast.LENGTH_LONG).show();
                    launch(path, name);
                });
            }).start();
        }, 600); // the old game process ends first
    }

    private static String title(File f) {
        String n = f.getName();
        if (f.isDirectory()) return n;
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private void refresh() {
        files.clear();
        File[] all = games.listFiles();
        if (all != null) {
            Arrays.sort(all, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            files.addAll(Arrays.asList(all));
        }
        adapter.clear();
        for (File f : files) adapter.add(title(f));
        empty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void launch(String path, String name) {
        File f = new File(path);
        if (GameFiles.isZip(f)) {
            // added by an older version as a zip: unpack once into a folder (the game gets C:\NAME)
            Toast.makeText(this, "Распаковываю " + name + "…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                File dir = unpack(f, name);
                if (dir != null) GameFiles.toMuseum(getFilesDir(), dir);
                ui.post(() -> {
                    refresh();
                    if (dir != null) launch(dir.getAbsolutePath(), dir.getName());
                    else Toast.makeText(this, "Архив не распаковывается", Toast.LENGTH_LONG).show();
                });
            }).start();
            return;
        }
        Intent i = new Intent(this, GameActivity.class);
        i.putExtra(GameActivity.EXTRA_PATH, path);
        i.putExtra(GameActivity.EXTRA_NAME, name);
        startActivity(i);
    }

    private void chooseAdd() {
        String[] items = {"📦  Архив .zip или образ диска (.img, .iso)", "📁  Папку с игрой целиком",
                "📄  Файлы игры (выделите все сразу)"};
        new AlertDialog.Builder(this)
                .setTitle("Добавить игру")
                .setItems(items, (d, w) -> {
                    if (w == 1) pickFolder();
                    else pick();
                })
                .show();
    }

    private void pickFolder() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_ADD_FOLDER);
        } catch (Exception e) {
            Toast.makeText(this, "Нет приложения для выбора папки", Toast.LENGTH_SHORT).show();
        }
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_ADD);
        } catch (Exception e) {
            Toast.makeText(this, "Нет приложения для выбора файла", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_ADD_FOLDER && data.getData() != null) {
            Uri tree = data.getData();
            new Thread(() -> {
                String root = DocumentsContract.getTreeDocumentId(tree);
                String name = docName(tree, root);
                File dst = unique(GameActivity.safe(name == null || name.isEmpty() ? "game" : name));
                int n = copyTree(tree, root, dst, 0);
                if (n > 0) GameFiles.toMuseum(getFilesDir(), dst);
                ui.post(() -> {
                    refresh();
                    Toast.makeText(this, n > 0 ? "Добавлено: " + dst.getName() + " (" + n + " файл.)"
                            : "Папка пуста или не читается", Toast.LENGTH_SHORT).show();
                });
            }).start();
            return;
        }
        if (req != REQ_ADD) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        new Thread(() -> {
            String first = displayName(uris.get(0));
            if (first == null || first.isEmpty()) first = "game.zip";
            first = GameActivity.safe(first);
            String low = first.toLowerCase(java.util.Locale.ROOT);
            boolean program = low.endsWith(".exe") || low.endsWith(".com") || low.endsWith(".bat");
            boolean ok = true;
            File shown;
            if (uris.size() == 1 && low.endsWith(".zip")) {
                // an archive: unpacked into a folder of its name (dates kept); the archive itself
                // stays where it was on the phone
                File tmp = new File(getCacheDir(), "import.zip");
                ok = copy(uris.get(0), tmp);
                File dir = ok ? unpack(tmp, first.substring(0, first.length() - 4)) : null;
                tmp.delete();
                ok = dir != null;
                shown = dir != null ? dir : new File(games, first);
            } else if (uris.size() == 1 && !program) {
                // a disk image: kept as one file
                shown = new File(games, first);
                ok = copy(uris.get(0), shown);
            } else {
                // several files, or a program: a folder of their own (DOSBox makes it drive C:)
                int dot = first.lastIndexOf('.');
                shown = unique(dot > 0 ? first.substring(0, dot) : first);
                shown.mkdirs();
                for (Uri u : uris) {
                    String n = displayName(u);
                    ok &= n != null && copy(u, new File(shown, GameActivity.safe(n)));
                }
            }
            if (ok) GameFiles.toMuseum(getFilesDir(), shown);
            boolean done = ok, lonely = uris.size() == 1 && program;
            File f = shown;
            ui.post(() -> {
                refresh();
                if (!done) Toast.makeText(this, "Не удалось скопировать", Toast.LENGTH_SHORT).show();
                else if (lonely) new AlertDialog.Builder(this)
                        .setTitle("Добавлена одна программа")
                        .setMessage("«" + f.getName() + "» — только запускаемый файл. Большинству игр нужны и остальные "
                                + "их файлы (графика, звук, уровни), иначе игра скажет, что не нашла их. Лучше добавить "
                                + "zip-архив или папку с игрой целиком; эту запись можно удалить долгим нажатием.")
                        .setPositiveButton("Понятно", null)
                        .show();
                else Toast.makeText(this, "Добавлено: " + title(f), Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    /** unpacks a zip copied into the app into a folder of the same name, then drops the copy */
    private File unpack(File zip, String name) {
        File dir = unique(GameActivity.safe(name));
        try {
            int n = GameFiles.unzip(zip, dir);
            if (n == 0) throw new IOException("empty");
        } catch (IOException | RuntimeException e) {
            deleteAll(dir);
            return null;
        }
        zip.delete();
        return dir;
    }

    /** a name in the games folder that is not taken yet */
    private File unique(String base) {
        File m = GameFiles.museum(getFilesDir());
        File f = new File(games, base);
        for (int i = 2; f.exists() || new File(m, f.getName()).exists(); i++) f = new File(games, base + " (" + i + ")");
        return f;
    }

    private String docName(Uri tree, String docId) {
        Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        try (Cursor c = getContentResolver().query(u, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /** copies a picked folder with its subfolders; returns the number of files */
    private int copyTree(Uri tree, String docId, File dst, int depth) {
        if (depth > 12) return 0;
        dst.mkdirs();
        int n = 0;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        try (Cursor c = getContentResolver().query(children, cols, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String id = c.getString(0), name = GameActivity.safe(c.getString(1));
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                    File sub = new File(dst, name);
                    n += copyTree(tree, id, sub, depth + 1);
                    long t = c.isNull(3) ? 0 : c.getLong(3);
                    if (t > 0) sub.setLastModified(t); // after its contents, which change it
                } else {
                    File out = new File(dst, name);
                    if (copy(DocumentsContract.buildDocumentUriUsingTree(tree, id), out)) {
                        long t = c.isNull(3) ? 0 : c.getLong(3);
                        if (t > 0) out.setLastModified(t); // the files' dates are part of the game
                        n++;
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return n;
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
        }
        return uri.getLastPathSegment();
    }

    private boolean copy(Uri uri, File dst) {
        File tmp = new File(dst.getPath() + ".tmp");
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) return false;
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        } catch (IOException | RuntimeException e) {
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(dst)) return false;
        long t = lastModified(uri);
        if (t > 0) dst.setLastModified(t); // the files' dates are part of the game
        return true;
    }

    private long lastModified(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (RuntimeException ignored) {
        }
        return 0;
    }

    private static void deleteAll(File f) {
        File[] in = f.listFiles();
        if (in != null) for (File x : in) deleteAll(x);
        f.delete();
    }

    private void confirmDelete(File f) {
        new AlertDialog.Builder(this)
                .setTitle(title(f))
                .setMessage("Удалить игру из приложения вместе с её музейной копией и сохранениями? "
                        + "Файлы на телефоне, из которых её добавляли, не трогаются.")
                .setPositiveButton("Удалить", (d, w) -> {
                    deleteAll(f);
                    GameFiles.deleteTree(new File(GameFiles.museum(getFilesDir()), f.getName()));
                    String base = GameActivity.safe(title(f));
                    new File(new File(getFilesDir(), "states"), base + ".quick").delete();
                    new File(new File(getFilesDir(), "states"), base + ".auto").delete();
                    refresh();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }
}
