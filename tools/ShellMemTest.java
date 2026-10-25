import com.alexspec.specialist.core.SpecKeys;
import com.alexspec.specialist.core.Specialist;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * The shell's "Память" program driven by key presses (ROM-002): go to an address, edit bytes,
 * scroll, search, copy blocks (both overlap directions). Checks memory and the cursor; saves
 * screenshots. Usage: java ShellMemTest shell.rom shell.sym outdir
 */
public class ShellMemTest {
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
        key("F1");
        key("2");
        run(100);
        shot("mem_start");

        type("F2", "8000", "ВК");
        run(30);
        type("ВК", "1", "2", "A", "B", "ВК");
        run(20);
        expect("правка 8000", 0x8000, 0x12, 0xAB);
        expectCur(0x8002);
        shot("mem_edit");
        for (int i = 0; i < 20; i++) key("↓");
        run(30);
        expectCur(0x8002 + 160);
        shot("mem_scroll");
        for (int i = 0; i < 20; i++) key("↑");
        run(30);
        expectCur(0x8002);

        type("F2", "C", "6", "0", "8", "ВК");
        run(40);
        shot("mem_font");

        type("F2", "1000", "ВК");
        run(40);                // the window is redrawn: keys pressed meanwhile are lost
        type("ВК", "DEADBEEF", "ВК");
        run(30);
        expect("правка 1000", 0x1000, 0xDE, 0xAD, 0xBE, 0xEF);
        type("F2", "0000", "ВК");
        run(30);
        type("F3", "DEADBEEF", "ВК");
        run(250);
        expectCur(0x1000);
        shot("mem_found");

        // copy 1000-1003 to 1002: destination inside the source -> must copy backwards
        type("F4", "1000", "ВК", "1003", "ВК", "1002", "ВК");
        run(30);
        expect("перенос назад", 0x1000, 0xDE, 0xAD, 0xDE, 0xAD, 0xBE, 0xEF);
        // copy 1002-1005 to 1001: forwards
        type("F4", "1002", "ВК", "1005", "ВК", "1001", "ВК");
        run(30);
        expect("перенос вперёд", 0x1000, 0xDE, 0xDE, 0xAD, 0xBE, 0xEF, 0xEF);
        // into ROM: refused, nothing written
        type("F4", "1000", "ВК", "1001", "ВК", "BFFF", "ВК");
        run(30);
        shot("mem_copy");
        // ПЗУ cannot be edited
        type("F2", "C000", "ВК", "ВК", "0", "0", "ВК");
        run(30);
        if (m.peek(0xC000) == 0) { System.out.println("FAIL: ROM changed"); fails++; }
        shot("mem_rom");
        type("F2", "9000", "ВК");
        run(30);
        shot("mem_screen");
        type("F2", "F800", "ВК");
        run(30);
        shot("mem_ppi");
        System.out.println(fails == 0 ? "MEMORY OK" : "MEMORY FAILED: " + fails);
        if (fails > 0) System.exit(1);
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

    static void expectCur(int want) {
        int c = m.peek(sym.get("MCUR")) | m.peek(sym.get("MCUR") + 1) << 8;
        if (c != want) { System.out.printf("FAIL cursor %04X, want %04X%n", c, want); fails++; }
        else System.out.printf("ok cursor %04X%n", c);
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
