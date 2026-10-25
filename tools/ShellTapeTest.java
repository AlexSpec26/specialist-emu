import com.alexspec.specialist.core.SpecKeys;
import com.alexspec.specialist.core.Specialist;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * "Магнитофон" (ROM-006) through the emulated tape: the shell writes a block, the signal is
 * recorded and played back, the shell reads it into cleared memory. Also: a block written by
 * Monitor-2's own routine (C827) must load in the shell, and a shell recording must load
 * with Monitor-2's reader (C824). Usage: java ShellTapeTest shell.rom shell.sym m2.rom outdir
 */
public class ShellTapeTest {
    static Specialist m;
    static short[] au = new short[4000];
    static Map<String, Integer> sym = new HashMap<>();
    static File out;
    static int fails;

    /** Recorded edges of the tape output, replayed as tape input. */
    static class Tape implements com.alexspec.specialist.core.TapeInput {
        final java.util.ArrayList<long[]> edges = new java.util.ArrayList<>();
        long shift;
        boolean level(int i) { return edges.get(i)[1] != 0; }
        public boolean level(long cycle) {
            long t = cycle - shift;
            int lo = 0, hi = edges.size() - 1, k = -1;
            while (lo <= hi) { int mid = (lo + hi) >>> 1; if (edges.get(mid)[0] <= t) { k = mid; lo = mid + 1; } else hi = mid - 1; }
            return k < 0 ? false : level(k);
        }
        /** start playing so that the first edge comes a little after "now" */
        void play(Specialist on) { shift = on.getCycleCount() + 200_000 - edges.get(0)[0]; on.tapeInput = this; }
    }

    static Tape record(Specialist on) {
        Tape t = new Tape();
        on.tapeOutListener = (cycle, level) -> t.edges.add(new long[]{cycle, level ? 1 : 0});
        return t;
    }

    public static void main(String[] a) throws Exception {
        for (String line : Files.readAllLines(Paths.get(a[1]))) {
            String[] p = line.trim().split("\\s+");
            if (p.length == 2) sym.put(p[1], Integer.parseInt(p[0], 16));
        }
        byte[] rom = Files.readAllBytes(Paths.get(a[0]));
        byte[] m2 = Files.readAllBytes(Paths.get(a[2]));
        out = new File(a[3]);
        out.mkdirs();
        m = new Specialist();
        m.loadRom(rom);
        m.hardReset();
        run(30);
        key("F1");
        key("ВК");
        run(60);
        key("F1");
        key("3");
        run(80);
        java.util.Random rnd = new java.util.Random(1);
        int[] data = new int[0x300];
        for (int i = 0; i < data.length; i++) { data[i] = rnd.nextInt(256); m.write(0x2000 + i, data[i]); }

        // 1. the shell writes 2000-22FF
        Tape t = record(m);
        type("F3", "2000", "ВК", "22FF", "ВК", "ВК", "ВК");   // no name, then start
        run(1500);
        m.tapeOutListener = null;
        System.out.println("recorded edges: " + t.edges.size());
        shot("tape_saved");

        // 2. clear and read it back
        for (int i = 0; i < data.length; i++) m.write(0x2000 + i, 0);
        key("F2");
        t.play(m);
        run(1500);
        m.tapeInput = null;
        expect("чтение своей записи", 0x2000, data);
        expectByte("сумма верна", sym.get("TSTAT"), 1);
        shot("tape_loaded");

        // 3. Monitor-2's writer -> the shell's reader
        Specialist w = new Specialist();
        w.loadRom(m2);
        w.hardReset();
        for (int i = 0; i < 60; i++) w.runFrame(au);
        int[] d2 = new int[0x100];
        for (int i = 0; i < d2.length; i++) { d2[i] = rnd.nextInt(256); w.write(0x3000 + i, d2[i]); }
        Tape t2 = new Tape();
        w.tapeOutListener = (cycle, level) -> t2.edges.add(new long[]{cycle, level ? 1 : 0});
        callAt(w, 0xC827, 0x3000, 0x30FF, 0);
        w.tapeOutListener = null;
        key("F2");
        t2.play(m);
        run(800);
        m.tapeInput = null;
        expect("чтение записи Монитора-2", 0x3000, d2);
        expectByte("сумма верна (Монитор-2)", sym.get("TSTAT"), 1);

        // 4. the shell's recording -> Monitor-2's reader (C824)
        Specialist r = new Specialist();
        r.loadRom(m2);
        r.hardReset();
        for (int i = 0; i < 60; i++) r.runFrame(au);
        t.play(r);
        callAt(r, 0xC824, 0, 0, 0);
        boolean ok = true;
        for (int i = 0; i < data.length; i++) if (r.peek(0x2000 + i) != data[i]) { ok = false; break; }
        if (!ok) { System.out.println("FAIL Монитор-2 не прочитал запись оболочки"); fails++; }
        else System.out.println("ok Монитор-2 читает запись оболочки");

        // 6. named files: the shell writes "TEST" -> Monitor-2's I reads it (any name: 8FC1 = 0)
        for (int i = 0; i < 0x40; i++) m.write(0x2400 + i, data[i]);
        Tape t4 = record(m);
        type("F3", "2400", "ВК", "243F", "ВК", "TEST", "ВК", "ВК");
        run(600);
        m.tapeOutListener = null;
        Specialist r2 = new Specialist();
        r2.loadRom(m2);
        r2.hardReset();
        for (int i = 0; i < 60; i++) r2.runFrame(au);
        r2.write(0x8FC1, 0);
        t4.play(r2);
        callAt(r2, 0xC9CB, 0, 0, 0);
        StringBuilder nm = new StringBuilder();
        for (int i = 0; i < 4; i++) nm.append((char) r2.peek(0x8FB0 + i));
        boolean ok4 = nm.toString().equals("TEST");
        for (int i = 0; i < 0x40; i++) if (r2.peek(0x2400 + i) != data[i]) ok4 = false;
        if (!ok4) { System.out.println("FAIL Монитор-2 (I) не прочитал именованный файл: " + nm); fails++; }
        else System.out.println("ok Монитор-2 (I) читает файл оболочки с именем " + nm);

        // 7. Monitor-2's O format (name header + block) -> the shell reads data and name
        Specialist w2 = new Specialist();
        w2.loadRom(m2);
        w2.hardReset();
        for (int i = 0; i < 60; i++) w2.runFrame(au);
        int[] d3 = new int[0x80];
        int sum = 0;
        for (int i = 0; i < d3.length; i++) { d3[i] = rnd.nextInt(256); w2.write(0x3400 + i, d3[i]); }
        // checksum as C82A: low = sum of bytes; high = sum with carries, except for the last byte
        int lo = 0, hi = 0;
        for (int i = 0; i < d3.length; i++) {
            lo += d3[i];
            if (i < d3.length - 1) { hi += d3[i] + (lo >> 8); }
            lo &= 0xFF; hi &= 0xFF;
        }
        w2.write(0x8F93, lo); w2.write(0x8F94, hi);
        String name = "ABC";
        for (int i = 0; i < name.length(); i++) w2.write(0x8FB0 + i, name.charAt(i));
        w2.write(0x8FB0 + name.length(), 0);
        Tape t5 = new Tape();
        w2.tapeOutListener = (cycle, level) -> t5.edges.add(new long[]{cycle, level ? 1 : 0});
        // enter after the name prompt (CAB0): the stack holds end (DE) and start (HL), then our return
        int ret = 0x0100;
        w2.cpu.sp = 0x7F00 - 6;
        int sp = w2.cpu.sp;
        w2.write(sp, 0x7F); w2.write(sp + 1, 0x34);      // DE = 347F
        w2.write(sp + 2, 0x00); w2.write(sp + 3, 0x34);  // HL = 3400
        w2.write(sp + 4, ret & 0xFF); w2.write(sp + 5, ret >> 8);
        w2.cpu.c = 0;
        w2.cpu.pc = 0xCAB0;
        long end2 = w2.getCycleCount() + 400_000_000L;
        while (w2.cpu.pc != ret && w2.getCycleCount() < end2) w2.runCycles(1);
        w2.tapeOutListener = null;
        key("F2");
        t5.play(m);
        run(600);
        m.tapeInput = null;
        expect("чтение файла Монитора-2 с именем", 0x3400, d3);
        StringBuilder nm2 = new StringBuilder();
        for (int i = 0; i < 3; i++) nm2.append((char) m.peek(sym.get("TNAME") + i));
        if (!nm2.toString().equals("ABC")) { System.out.println("FAIL имя: " + nm2); fails++; }
        else System.out.println("ok имя файла " + nm2);
        expectByte("сумма верна (O)", sym.get("TSTAT"), 1);
        shot("tape_named");

        // 5. F5 runs the loaded block; its RET comes back into the shell, state kept (warm start)
        int[] prog = {0x3E, 0x77, 0x32, 0x00, 0x21, 0xC9};      // MVI A,77 / STA 2100 / RET
        for (int i = 0; i < prog.length; i++) m.write(0x2000 + i, prog[i]);
        Tape t3 = record(m);
        type("F3", "2000", "ВК", "2005", "ВК", "ВК", "ВК");
        run(400);
        m.tapeOutListener = null;
        for (int i = 0; i < prog.length; i++) m.write(0x2000 + i, 0);
        key("F2");
        t3.play(m);
        run(400);
        m.tapeInput = null;
        expect("программа загружена", 0x2000, prog);
        key("F5");
        run(80);
        expectByte("программа выполнилась", 0x2100, 0x77);
        expectByte("после RET снова Магнитофон", sym.get("CURAPP"), 2);
        expectByte("состояние сохранилось", sym.get("TSTAT"), 1);
        shot("tape_after_run");

        System.out.println(fails == 0 ? "TAPE OK" : "TAPE FAILED: " + fails);
        if (fails > 0) System.exit(1);
    }

    /** Calls a ROM routine with HL, DE, BC set and runs until it returns. */
    static void callAt(Specialist s, int addr, int hl, int de, int bc) {
        int ret = 0x0100;
        s.cpu.sp = 0x7F00 - 2;
        s.write(s.cpu.sp, ret & 0xFF);
        s.write(s.cpu.sp + 1, ret >> 8);
        s.cpu.h = hl >> 8; s.cpu.l = hl & 0xFF;
        s.cpu.d = de >> 8; s.cpu.e = de & 0xFF;
        s.cpu.b = bc >> 8; s.cpu.c = bc & 0xFF;
        s.cpu.pc = addr;
        long end = s.getCycleCount() + 400_000_000L;
        while (s.cpu.pc != ret && s.getCycleCount() < end) s.runCycles(1);   // one command at a time
        if (s.cpu.pc != ret) { System.out.println("FAIL: " + Integer.toHexString(addr) + " did not return"); fails++; }
    }

    static void expectByte(String what, int addr, int v) {
        if (m.peek(addr) != v) { System.out.printf("FAIL %s: %02X, want %02X%n", what, m.peek(addr), v); fails++; }
        else System.out.println("ok " + what);
    }

    static void expect(String what, int addr, int... bytes) {
        for (int i = 0; i < bytes.length; i++)
            if (m.peek(addr + i) != bytes[i]) {
                System.out.printf("FAIL %s: %04X = %02X, want %02X%n", what, addr + i, m.peek(addr + i), bytes[i]);
                fails++;
                return;
            }
        System.out.println("ok " + what);
    }

    static void type(String... keys) {
        for (String k : keys) {
            if (k.matches("F([1-9]|1[01])|ВК|↓|↑")) key(k);          // a named key
            else for (char ch : k.toCharArray()) key(String.valueOf(ch));
        }
    }

    static void key(String label) {
        int code;
        switch (label) {
            case "ВК": code = SpecKeys.ENTER; break;
            case "↓": code = SpecKeys.DOWN; break;
            case "↑": code = SpecKeys.UP; break;
            default: code = SpecKeys.byLabel(label);
        }
        if (code < 0) throw new IllegalArgumentException("no key " + label);
        m.keyDown(code);
        run(3);
        m.keyUp(code);
        run(12);                // typing speed ~4 keys/s: a key pressed while drawing is missed
    }

    static void run(int frames) {
        for (int i = 0; i < frames; i++) m.runFrame(au);
    }

    static void shot(String name) throws Exception {
        int[] p = new int[Specialist.WIDTH * Specialist.HEIGHT];
        m.render(p, 0);
        BufferedImage img = new BufferedImage(Specialist.WIDTH * 2, Specialist.HEIGHT * 2, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < Specialist.HEIGHT * 2; y++)
            for (int x = 0; x < Specialist.WIDTH * 2; x++) img.setRGB(x, y, p[(y / 2) * Specialist.WIDTH + x / 2]);
        javax.imageio.ImageIO.write(img, "png", new File(out, name + ".png"));
    }
}
