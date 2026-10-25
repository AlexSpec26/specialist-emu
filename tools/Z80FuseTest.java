import com.alexspec.zx.core.Z80;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the FUSE Z80 test-suite (tests.in / tests.expected): compares registers, memory,
 * final T-states and the full list of bus events (memory/port reads, writes, contention).
 * Usage: java Z80FuseTest tests.in tests.expected [-v]
 */
public class Z80FuseTest {
    static int[] mem = new int[65536];

    public static void main(String[] args) throws Exception {
        List<String> in = Files.readAllLines(Paths.get(args[0]));
        List<String> ex = Files.readAllLines(Paths.get(args[1]));
        boolean verbose = args.length > 2;
        int pi = 0, pe = 0, pass = 0, fail = 0;
        List<String> failed = new ArrayList<>();
        while (true) {
            while (pi < in.size() && in.get(pi).trim().isEmpty()) pi++;
            if (pi >= in.size()) break;
            String name = in.get(pi++).trim();
            String[] r1 = in.get(pi++).trim().split("\\s+");
            String[] r2 = in.get(pi++).trim().split("\\s+");
            java.util.Arrays.fill(mem, 0);
            // memory setup
            for (String line = in.get(pi++).trim(); !line.equals("-1"); line = in.get(pi++).trim()) {
                String[] p = line.split("\\s+");
                int addr = Integer.parseInt(p[0], 16);
                for (int k = 1; k < p.length && !p[k].equals("-1"); k++) mem[(addr + k - 1) & 0xFFFF] = Integer.parseInt(p[k], 16);
            }
            int[] initial = mem.clone();
            List<String> events = new ArrayList<>();
            Z80 cpu = new Z80(new Z80.Bus() {
                public int read(int a) { return mem[a]; }
                public void write(int a, int v) { mem[a] = v; }
                public int in(int port) { return port >> 8; }
                public void out(int port, int v) { }
                public boolean contended(int a) { return false; }
                public int delay(long t) { return 0; }
            });
            cpu.listener = (t, type, addr, data) -> events.add(String.format("%5d %s %04x%s", t, type, addr, data < 0 ? "" : String.format(" %02x", data)));
            cpu.setAF(h(r1[0])); cpu.setBC(h(r1[1])); cpu.setDE(h(r1[2])); cpu.setHL(h(r1[3]));
            int af_ = h(r1[4]), bc_ = h(r1[5]), de_ = h(r1[6]), hl_ = h(r1[7]);
            cpu.a_ = af_ >> 8; cpu.f_ = af_ & 0xFF; cpu.b_ = bc_ >> 8; cpu.c_ = bc_ & 0xFF;
            cpu.d_ = de_ >> 8; cpu.e_ = de_ & 0xFF; cpu.h_ = hl_ >> 8; cpu.l_ = hl_ & 0xFF;
            cpu.ix = h(r1[8]); cpu.iy = h(r1[9]); cpu.sp = h(r1[10]); cpu.pc = h(r1[11]);
            cpu.memptr = r1.length > 12 ? h(r1[12]) : 0;
            cpu.i = h(r2[0]); cpu.setR(h(r2[1]));
            cpu.iff1 = !r2[2].equals("0"); cpu.iff2 = !r2[3].equals("0");
            cpu.im = Integer.parseInt(r2[4]); cpu.halted = !r2[5].equals("0");
            int tst = Integer.parseInt(r2[6]);
            cpu.t = 0;
            while (cpu.t < tst) cpu.step();

            // expected
            while (pe < ex.size() && ex.get(pe).trim().isEmpty()) pe++;
            String ename = ex.get(pe++).trim();
            if (!ename.equals(name)) throw new IllegalStateException("desync " + name + " vs " + ename);
            List<String> expEvents = new ArrayList<>();
            while (ex.get(pe).startsWith(" ")) {
                String[] p = ex.get(pe++).trim().split("\\s+");
                expEvents.add(String.format("%5d %s %s%s", Integer.parseInt(p[0]), p[1], p[2], p.length > 3 ? " " + p[3] : ""));
            }
            String[] e1 = ex.get(pe++).trim().split("\\s+");
            String[] e2 = ex.get(pe++).trim().split("\\s+");
            int[] expMem = initial.clone();
            while (pe < ex.size() && !ex.get(pe).trim().isEmpty()) {
                String[] p = ex.get(pe++).trim().split("\\s+");
                int addr = Integer.parseInt(p[0], 16);
                for (int k = 1; k < p.length && !p[k].equals("-1"); k++) expMem[(addr + k - 1) & 0xFFFF] = Integer.parseInt(p[k], 16);
            }
            List<String> errs = new ArrayList<>();
            chk(errs, "AF", cpu.af(), h(e1[0])); chk(errs, "BC", cpu.bc(), h(e1[1]));
            chk(errs, "DE", cpu.de(), h(e1[2])); chk(errs, "HL", cpu.hl(), h(e1[3]));
            chk(errs, "AF'", (cpu.a_ << 8) | cpu.f_, h(e1[4])); chk(errs, "BC'", (cpu.b_ << 8) | cpu.c_, h(e1[5]));
            chk(errs, "DE'", (cpu.d_ << 8) | cpu.e_, h(e1[6])); chk(errs, "HL'", (cpu.h_ << 8) | cpu.l_, h(e1[7]));
            chk(errs, "IX", cpu.ix, h(e1[8])); chk(errs, "IY", cpu.iy, h(e1[9]));
            chk(errs, "SP", cpu.sp, h(e1[10])); chk(errs, "PC", cpu.pc, h(e1[11]));
            if (e1.length > 12) chk(errs, "MEMPTR", cpu.memptr, h(e1[12]));
            chk(errs, "I", cpu.i, h(e2[0])); chk(errs, "R", cpu.getR(), h(e2[1]));
            chk(errs, "IFF1", cpu.iff1 ? 1 : 0, Integer.parseInt(e2[2]));
            chk(errs, "IFF2", cpu.iff2 ? 1 : 0, Integer.parseInt(e2[3]));
            chk(errs, "IM", cpu.im, Integer.parseInt(e2[4]));
            chk(errs, "HALT", cpu.halted ? 1 : 0, Integer.parseInt(e2[5]));
            chk(errs, "T", (int) cpu.t, Integer.parseInt(e2[6]));
            for (int a = 0; a < 65536; a++) if (mem[a] != expMem[a]) { errs.add(String.format("mem %04x=%02x exp %02x", a, mem[a], expMem[a])); break; }
            if (!events.equals(expEvents)) errs.add("events differ");
            if (errs.isEmpty()) pass++;
            else {
                fail++;
                failed.add(name + ": " + String.join(", ", errs));
                if (verbose && fail <= 6) {
                    System.out.println("== " + name + " " + errs);
                    System.out.println(" got: " + events);
                    System.out.println(" exp: " + expEvents);
                }
            }
        }
        System.out.println("pass " + pass + " fail " + fail);
        for (int k = 0; k < Math.min(60, failed.size()); k++) System.out.println("  " + failed.get(k));
    }

    static int h(String s) { return Integer.parseInt(s, 16); }

    static void chk(List<String> errs, String n, int got, int exp) {
        if (got != exp) errs.add(String.format("%s=%04x exp %04x", n, got, exp));
    }
}
