import com.alexspec.specialist.core.Bus;
import com.alexspec.specialist.core.I8080;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Runs classic CP/M 8080 test programs (8080PRE, TST8080, CPUTEST, 8080EXM) against the core.
 * Usage: java CpuTest file.com ...
 */
public class CpuTest {
    public static void main(String[] args) throws Exception {
        for (String file : args) {
            final int[] mem = new int[65536];
            byte[] prog = Files.readAllBytes(Paths.get(file));
            for (int i = 0; i < prog.length; i++) mem[0x100 + i] = prog[i] & 0xFF;
            mem[0x0005] = 0xC9; // RET at BDOS entry; calls are intercepted below
            Bus bus = new Bus() {
                public int read(int a) { return mem[a]; }
                public void write(int a, int v) { mem[a] = v; }
                public int in(int p) { return 0xFF; }
                public void out(int p, int v) { }
            };
            I8080 cpu = new I8080(bus);
            cpu.reset(0x100);
            System.out.println("=== " + file);
            long cycles = 0;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (cpu.pc == 0x0005) {
                    if (cpu.c == 2) out.append((char) cpu.e);
                    else if (cpu.c == 9) {
                        for (int a = cpu.de(); mem[a] != '$'; a++) out.append((char) mem[a]);
                    }
                    if (out.indexOf("\n") >= 0) { System.out.print(out); out.setLength(0); }
                } else if (cpu.pc == 0x0000) {
                    break;
                }
                cycles += cpu.step();
            }
            System.out.println(out);
            System.out.println("cycles=" + cycles);
        }
    }
}
