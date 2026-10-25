import com.alexspec.specialist.core.SpecKeys;
import com.alexspec.specialist.core.Specialist;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * CI smoke test of the shell ROM: power on, "PROGRAM ?" -> F1 -> ВК, the shell must draw its
 * inverted menu line and wait for keys inside its own code.
 * Usage: java ShellSmoke shell.rom
 */
public class ShellSmoke {
    public static void main(String[] a) throws Exception {
        Specialist m = new Specialist();
        m.loadRom(Files.readAllBytes(Paths.get(a[0])));
        m.hardReset();
        short[] au = new short[4000];
        for (int i = 0; i < 30; i++) m.runFrame(au);
        press(m, SpecKeys.byLabel("F1"), au);
        press(m, SpecKeys.ENTER, au);
        for (int i = 0; i < 150; i++) m.runFrame(au);
        int pc = m.cpu.pc;
        // menu line: text row 0 is inverted, so most of its bytes have many bits set
        int set = 0;
        for (int col = 0; col < 48; col++)
            for (int y = 0; y < 8; y++) set += Integer.bitCount(m.peek(0x9000 + col * 256 + y));
        System.out.printf("pc=%04X, menu line pixels set: %d of %d%n", pc, set, 48 * 8 * 8);
        boolean ok = pc >= 0xC836 && pc < 0xF000 && set > 48 * 8 * 8 / 2;
        System.out.println(ok ? "SHELL OK" : "SHELL FAILED");
        if (!ok) System.exit(1);
    }

    static void press(Specialist m, int key, short[] au) {
        m.keyDown(key);
        for (int i = 0; i < 4; i++) m.runFrame(au);
        m.keyUp(key);
        for (int i = 0; i < 6; i++) m.runFrame(au);
    }
}
