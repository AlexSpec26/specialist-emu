import com.alexspec.zx.core.Z80;

import java.nio.file.Files;
import java.nio.file.Paths;

/** Runs zexdoc / zexall (CP/M .com/.cim at 0100h) on the Z80 core. */
public class ZexTest {
    public static void main(String[] args) throws Exception {
        for (String file : args) {
            final int[] mem = new int[65536];
            byte[] prog = Files.readAllBytes(Paths.get(file));
            for (int i = 0; i < prog.length; i++) mem[0x100 + i] = prog[i] & 0xFF;
            mem[5] = 0xC9;
            Z80 cpu = new Z80(new Z80.Bus() {
                public int read(int a) { return mem[a]; }
                public void write(int a, int v) { mem[a] = v; }
                public int in(int p) { return 0xFF; }
                public void out(int p, int v) { }
                public boolean contended(int a) { return false; }
                public int delay(long t) { return 0; }
            });
            cpu.reset();
            cpu.pc = 0x100;
            cpu.sp = 0xF000;
            System.out.println("=== " + file);
            long t0 = System.nanoTime();
            while (true) {
                if (cpu.pc == 5) {
                    if (cpu.c == 2) System.out.print((char) cpu.e);
                    else if (cpu.c == 9) for (int a = cpu.de(); mem[a] != '$'; a++) System.out.print((char) mem[a]);
                    System.out.flush();
                } else if (cpu.pc == 0) break;
                cpu.step();
            }
            System.out.printf("%n%.1f s, %d T-states%n", (System.nanoTime() - t0) / 1e9, cpu.t);
        }
    }
}
