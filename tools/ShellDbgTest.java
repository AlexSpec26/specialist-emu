import com.alexspec.specialist.core.SpecKeys;
import com.alexspec.specialist.core.Specialist;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * The shell's "Отладка" driven by key presses (ROM-003): steps into and over a CALL, a loop,
 * breakpoints, the program's last RET; checks registers and that every changed byte (traps,
 * the 0038 vector) is put back. Usage: java ShellDbgTest shell.rom shell.sym outdir
 */
public class ShellDbgTest {
    static Specialist m;
    static short[] au = new short[4000];
    static Map<String, Integer> sym = new HashMap<>();
    static File out;
    static int fails;

    public static void main(String[] a) throws Exception {
        for (String line : Files.readAllLines(Paths.get(a[1]))) {
            String[] p = line.trim().split("\\s+");
            if (p.length == 2) sym.put(p[1], Integer.parseInt(p[0], 16));
        }
        out = new File(a[2]);
        out.mkdirs();
        m = new Specialist();
        m.loadRom(Files.readAllBytes(Paths.get(a[0])));
        m.hardReset();
        run(30);
        key("F1");
        key("ВК");
        run(60);
        int[] prog = {
            0x3E, 0x05,             // 0100 MVI A,5
            0x06, 0x03,             // 0102 MVI B,3
            0xCD, 0x10, 0x01,       // 0104 CALL 0110
            0x80,                   // 0107 ADD B
            0xC9,                   // 0108 RET
            0, 0, 0, 0, 0, 0, 0,
            0x3C,                   // 0110 INR A
            0xC9,                   // 0111 RET
        };
        int[] loop = {
            0x06, 0x03,             // 0120 MVI B,3
            0x05,                   // 0122 DCR B
            0xC2, 0x22, 0x01,       // 0123 JNZ 0122
            0xC9,                   // 0126 RET
        };
        for (int i = 0; i < prog.length; i++) m.write(0x100 + i, prog[i]);
        for (int i = 0; i < loop.length; i++) m.write(0x120 + i, loop[i]);
        m.write(0x38, 0x11); m.write(0x39, 0x22); m.write(0x3A, 0x33);
        key("F1");
        key("3");
        run(100);
        shot("dbg_start");

        type("F2", "0100", "ВК");
        run(40);
        step("F5", 0x0102); reg("A", 0x05);
        step("F5", 0x0104); reg("B", 0x03);
        step("F5", 0x0110);
        step("F5", 0x0111); reg("A", 0x06);
        shot("dbg_incall");
        step("F5", 0x0107);
        step("F5", 0x0108); reg("A", 0x09);
        step("F5", sym.get("DBG_DONE"));
        shot("dbg_done");

        type("F2", "0100", "ВК");
        run(40);
        step("F6", 0x0102);
        step("F6", 0x0104);
        step("F6", 0x0107); reg("A", 0x06);

        type("F8", "0108", "ВК");
        run(30);
        type("F2", "0100", "ВК");
        run(40);
        step("F9", 0x0108); reg("A", 0x09);
        shot("dbg_break");
        step("F9", sym.get("DBG_DONE"));
        type("F8", "0108", "ВК");   // remove it
        run(30);

        type("F2", "0120", "ВК");
        run(40);
        step("F5", 0x0122);
        step("F5", 0x0123);
        step("F5", 0x0122);         // the loop jumps back
        type("F8", "0126", "ВК");
        run(30);
        step("F9", 0x0126); reg("B", 0x00);
        shot("dbg_loop");

        expect("программа цела", 0x100, prog);
        expect("цикл цел", 0x120, loop);
        expect("вектор 0038 цел", 0x38, 0x11, 0x22, 0x33);
        System.out.println(fails == 0 ? "DEBUGGER OK" : "DEBUGGER FAILED: " + fails);
        if (fails > 0) System.exit(1);
    }

    static int word(String name) {
        int at = sym.get(name);
        return m.peek(at) | m.peek(at + 1) << 8;
    }

    static void step(String k, int pc) {
        key(k);
        run(45);                    // the debugger redraws after every stop
        int got = word("R_PC");
        if (got != pc) { System.out.printf("FAIL %s: PC %04X, want %04X%n", k, got, pc); fails++; }
        else System.out.printf("ok %s -> PC %04X%n", k, pc);
    }

    static void reg(String r, int v) {
        int got = r.equals("A") ? word("R_AF") >> 8 : r.equals("B") ? word("R_BC") >> 8 : -1;
        if (got != v) { System.out.printf("FAIL %s = %02X, want %02X%n", r, got, v); fails++; }
        else System.out.printf("ok %s = %02X%n", r, v);
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
