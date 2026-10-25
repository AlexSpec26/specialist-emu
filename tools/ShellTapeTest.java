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
        type("F3", "2000", "ВК", "22FF", "ВК", "ВК");
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

        // 5. F5 runs the loaded block; its RET comes back into the shell, state kept (warm start)
        int[] prog = {0x3E, 0x77, 0x32, 0x00, 0x21, 0xC9};      // MVI A,77 / STA 2100 / RET
        for (int i = 0; i < prog.length; i++) m.write(0x2000 + i, prog[i]);
        Tape t3 = record(m);
        type("F3", "2000", "ВК", "2005", "ВК", "ВК");
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
