import com.alexspec.specialist.core.SpecKeys;
import com.alexspec.specialist.core.Specialist;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Speed of the shell's screen routines (ROM-001): boots the shell, then calls routines by their
 * symbol names and counts CPU cycles (2 MHz). Also saves the screen after each redraw, so that a
 * faster text routine can be checked byte for byte against the old one.
 * Usage: java ShellTiming shell.rom shell.sym outdir [limit-ms-overview]
 */
public class ShellTiming {
    static Specialist m;
    static Map<String, Integer> sym = new HashMap<>();
    static Map<String, Long> profile;
    static java.util.TreeMap<Integer, String> code = new java.util.TreeMap<>();

    static String owner(int pc) {
        Map.Entry<Integer, String> e = code.floorEntry(pc);
        return e == null ? "?" : e.getValue();
    }

    /** Cycles per routine for one call, largest first. */
    static void profileOf(String name) {
        profile = new HashMap<>();
        callRegs(name);
        profile.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).limit(12)
                .forEach(e -> System.out.printf("    %-14s %8d%n", e.getKey(), e.getValue()));
        profile = null;
    }

    public static void main(String[] a) throws Exception {
        for (String line : Files.readAllLines(Paths.get(a[1]))) {
            String[] p = line.trim().split("\\s+");
            if (p.length == 2) {
                int v = Integer.parseInt(p[0], 16);
                sym.put(p[1], v);
                if (v >= 0xC000 && v < 0xF000 && !p[1].contains(".")) code.put(v, p[1]);
            }
        }
        File out = new File(a[2]);
        out.mkdirs();
        m = new Specialist();
        m.loadRom(Files.readAllBytes(Paths.get(a[0])));
        m.hardReset();
        short[] au = new short[4000];
        for (int i = 0; i < 30; i++) m.runFrame(au);
        press(SpecKeys.byLabel("F1"), au);
        press(SpecKeys.ENTER, au);
        for (int i = 0; i < 150; i++) m.runFrame(au);

        long cls = call("CLS");
        report("CLS (clear screen)", cls);
        long ov = call("REDRAW");
        report("REDRAW Обзор", ov);
        save(out, "overview");
        m.write(sym.get("CURAPP"), 2);
        long st = call("REDRAW");
        report("REDRAW Магнитофон", st);
        save(out, "stub");
        if (sym.containsKey("MEM_DRAW")) {
            m.write(sym.get("CURAPP"), 1);
            report("REDRAW Память", call("REDRAW"));
            save(out, "memory");
            m.write(sym.get("TMP"), 0x18);                      // →: cursor only
            report("Память: → (курсор)", call("MEM_KEY"));
            m.write(sym.get("TMP"), 0x1A);                      // ↓: new line in the picture
            report("Память: ↓", call("MEM_KEY"));
            for (int i = 0; i < 14; i++) call("MEM_KEY");
            report("Память: ↓ с прокруткой", call("MEM_KEY"));
        }
        if (sym.containsKey("DBG_DRAW")) {
            m.write(sym.get("CURAPP"), 3);
            report("REDRAW Отладка (каждая остановка)", call("REDRAW"));
            save(out, "debugger");
        }
        m.write(sym.get("CURAPP"), 0);
        // one full text row: 64 characters, normal and inverted
        long row = callRow(false);
        report("строка 64 символа", row);
        long rowi = callRow(true);
        report("строка 64 символа, инверсия", rowi);
        // scrolling rows 1..30 up and down; the result is checked against plain array moves
        call("REDRAW");
        int[] before = screen();
        m.cpu.b = 1; m.cpu.c = 30; m.cpu.e = 48;
        long up = callRegs("SCROLLUP");
        report("прокрутка вверх, строки 1–30", up);
        check("SCROLLUP", before, screen(), 1, 30, true);
        before = screen();
        m.cpu.b = 1; m.cpu.c = 30; m.cpu.e = 48;
        long dn = callRegs("SCROLLDN");
        report("прокрутка вниз, строки 1–30", dn);
        check("SCROLLDN", before, screen(), 1, 30, false);
        before = screen();
        m.cpu.b = 0; m.cpu.c = 31; m.cpu.e = 48;
        callRegs("SCROLLDN");
        check("SCROLLDN 0–31", before, screen(), 0, 31, false);
        before = screen();
        m.cpu.b = 0; m.cpu.c = 31; m.cpu.e = 48;
        callRegs("SCROLLUP");
        check("SCROLLUP 0–31", before, screen(), 0, 31, true);
        if (System.getProperty("profile") != null) {
            System.out.println("профиль REDRAW Обзор:");
            profileOf("REDRAW");
        }
        if (a.length > 3) {
            double limit = Double.parseDouble(a[3]);
            if (ov / 2000.0 > limit) {
                System.out.println("TOO SLOW: overview " + ov / 2000.0 + " ms > " + limit);
                System.exit(1);
            }
        }
    }

    static int[] screen() {
        int[] r = new int[0x3000];
        for (int i = 0; i < r.length; i++) r[i] = m.peek(0x9000 + i);
        return r;
    }

    static void check(String what, int[] b, int[] a, int top, int bot, boolean upward) {
        for (int col = 0; col < 48; col++)
            for (int y = 0; y < 256; y++) {
                int row = y / 8, want;
                if (row < top || row > bot) want = b[col * 256 + y];
                else if (upward) want = row == bot ? 0 : b[col * 256 + y + 8];
                else want = row == top ? 0 : b[col * 256 + y - 8];
                if (a[col * 256 + y] != want) {
                    System.out.printf("%s WRONG at column %d, line %d%n", what, col, y);
                    System.exit(1);
                }
            }
        System.out.println(what + " верно");
    }

    static void report(String what, long cycles) {
        System.out.printf("%-30s %9d тактов  %7.1f мс%n", what, cycles, cycles / 2000.0);
    }

    static long callRow(boolean inv) {
        m.write(sym.get("INV"), inv ? 0x3F : 0);
        m.write(sym.get("CURX"), 0);
        m.write(sym.get("CURY"), 10);
        int buf = 0x6000;
        for (int i = 0; i < 64; i++) m.write(buf + i, 0x41 + (i % 26));
        m.write(buf + 64, 0);
        m.cpu.h = buf >> 8; m.cpu.l = buf & 0xFF;
        long c = callRegs("PUTS");
        m.write(sym.get("INV"), 0);
        return c;
    }

    static long call(String name) { return callRegs(name); }

    /** Calls a routine with the registers already set; returns cycles until it returns. */
    static long callRegs(String name) {
        Integer addr = sym.get(name);
        if (addr == null) throw new IllegalArgumentException("no symbol " + name);
        int ret = 0x0100;
        m.cpu.sp = 0x7F00;
        m.cpu.sp -= 2;
        m.write(m.cpu.sp, ret & 0xFF);
        m.write(m.cpu.sp + 1, ret >> 8);
        m.cpu.pc = addr;
        long cyc = 0;
        while (m.cpu.pc != ret) {
            int pc = m.cpu.pc;
            int c = m.cpu.step();
            cyc += c;
            if (profile != null) profile.merge(owner(pc), (long) c, Long::sum);
            if (cyc > 40_000_000) throw new IllegalStateException(name + " does not return");
        }
        return cyc;
    }

    static void save(File dir, String name) throws Exception {
        byte[] scr = new byte[0x3000];
        for (int i = 0; i < scr.length; i++) scr[i] = (byte) m.peek(0x9000 + i);
        Files.write(new File(dir, name + ".bin").toPath(), scr);
        BufferedImage img = new BufferedImage(384 * 2, 256 * 2, BufferedImage.TYPE_INT_RGB);
        for (int col = 0; col < 48; col++)
            for (int y = 0; y < 256; y++) {
                int b = scr[col * 256 + y] & 0xFF;
                for (int bit = 0; bit < 8; bit++) {
                    int rgb = (b & (0x80 >> bit)) != 0 ? 0xE0E0E0 : 0x101010;
                    int x = col * 8 + bit;
                    for (int dy = 0; dy < 2; dy++)
                        for (int dx = 0; dx < 2; dx++) img.setRGB(x * 2 + dx, y * 2 + dy, rgb);
                }
            }
        javax.imageio.ImageIO.write(img, "png", new File(dir, name + ".png"));
    }

    static void press(int key, short[] au) {
        m.keyDown(key);
        for (int i = 0; i < 4; i++) m.runFrame(au);
        m.keyUp(key);
        for (int i = 0; i < 6; i++) m.runFrame(au);
    }
}
