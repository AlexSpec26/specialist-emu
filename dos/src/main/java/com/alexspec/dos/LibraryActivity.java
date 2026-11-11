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
    static final String EXTRA_RELAUNCH_PATH = "relaunchPath", EXTRA_RELAUNCH_NAME = "relaunchName";
    private static final int REQ_ADD = 1;

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
        add.setOnClickListener(v -> pick());
        Button bare = new Button(this);
        bare.setText("DOS без игры");
        bare.setOnClickListener(v -> launch("", "DOS"));
        row.addView(add, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(bare, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(row);

        empty = new TextView(this);
        empty.setText("Игр пока нет. Нажмите «Добавить игру» и выберите файл игры: архив .zip (как скачивают "
                + "старые и бесплатные игры), программу .exe/.com или образ диска .img/.iso. Файл копируется "
                + "в приложение.\n\nДолгое нажатие на игру — удалить её.");
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
        i.removeExtra(EXTRA_RELAUNCH_PATH);
        ui.postDelayed(() -> launch(path, name), 400); // the old game process ends first
    }

    private static String title(File f) {
        String n = f.getName();
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
        Intent i = new Intent(this, GameActivity.class);
        i.putExtra(GameActivity.EXTRA_PATH, path);
        i.putExtra(GameActivity.EXTRA_NAME, name);
        startActivity(i);
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
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
        if (req != REQ_ADD || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = displayName(uri);
        if (name == null || name.isEmpty()) name = "game.zip";
        name = GameActivity.safe(name);
        File dst = new File(games, name);
        String n = name;
        new Thread(() -> {
            boolean ok = copy(uri, dst);
            ui.post(() -> {
                refresh();
                Toast.makeText(this, ok ? "Добавлено: " + title(dst) : "Не удалось скопировать " + n, Toast.LENGTH_SHORT).show();
            });
        }).start();
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
        return tmp.renameTo(dst);
    }

    private void confirmDelete(File f) {
        new AlertDialog.Builder(this)
                .setTitle(title(f))
                .setMessage("Удалить игру из приложения? Её сохранения тоже удалятся.")
                .setPositiveButton("Удалить", (d, w) -> {
                    f.delete();
                    String base = GameActivity.safe(title(f));
                    new File(new File(getFilesDir(), "states"), base + ".quick").delete();
                    new File(new File(getFilesDir(), "states"), base + ".auto").delete();
                    refresh();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }
}
