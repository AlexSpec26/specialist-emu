package com.alexspec.atari;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.alexspec.atari.core.Atari65XE;
import java.util.Locale;

/**
 * Initial memory inspector. Real emulation/video/audio and game loading are
 * deliberately not claimed until the CPU and custom chips are implemented.
 */
public final class AtariActivity extends Activity {
    private final Atari65XE machine = new Atari65XE();
    private TextView memory;
    private int page = 0x0000;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);
        TextView heading = new TextView(this);
        heading.setText("Atari 65XE · memory inspector (development build)");
        heading.setTextSize(19);
        root.addView(heading);
        TextView note = new TextView(this);
        note.setText("PAL 1.77 MHz · 64 KiB RAM · emulator core under construction. No games run yet.");
        root.addView(note);
        LinearLayout nav = new LinearLayout(this);
        Button prev = new Button(this);
        prev.setText("◀ -256");
        prev.setOnClickListener(v -> { page = (page - 256) & 0xFFFF; refresh(); });
        nav.addView(prev, new LinearLayout.LayoutParams(0, -2, 1));
        Button next = new Button(this);
        next.setText("+256 ▶");
        next.setOnClickListener(v -> { page = (page + 256) & 0xFFFF; refresh(); });
        nav.addView(next, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(nav);
        ScrollView scroll = new ScrollView(this);
        memory = new TextView(this);
        memory.setTextSize(12);
        memory.setTypeface(android.graphics.Typeface.MONOSPACE);
        memory.setGravity(Gravity.START);
        scroll.addView(memory);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        refresh();
    }

    private void refresh() {
        StringBuilder s = new StringBuilder();
        s.append(String.format(Locale.US, "Page $%04X — %s\n\n", page, machine.region(page)));
        for (int i = 0; i < 256; i += 16) {
            s.append(String.format(Locale.US, "%04X  ", (page + i) & 0xFFFF));
            for (int j = 0; j < 16; j++)
                s.append(String.format(Locale.US, "%02X ", machine.read(page + i + j)));
            s.append("  ");
            for (int j = 0; j < 16; j++) {
                int c = machine.read(page + i + j);
                s.append(c >= 32 && c < 127 ? (char) c : '.');
            }
            s.append('\n');
        }
        memory.setText(s.toString());
    }
}