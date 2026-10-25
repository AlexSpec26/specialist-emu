import com.alexspec.specialist.core.Specialist;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Disassembler and one-line assembler of the devel ROM: every opcode 00-FF (operands 34 12) is
 * disassembled and compared with tools/dis8080_expected.txt (made by shell/tools/dis80.py), then
 * the text is assembled back and must give the same bytes (undocumented opcodes give their
 * documented twins). Also lines in small letters, an "H" suffix and wrong lines.
 * Usage: java ShellDisTest devel.rom devel.sym dis8080_expected.txt
 */
public class ShellDisTest {
    static Specialist m;
    static Map<String, Integer> sym = new HashMap<>();
    static int fails;

    public static void main(String[] a) throws Exception {
        for (String line : Files.readAllLines(Paths.get(a[1]))) {
            String[] p = line.trim().split("\\s+");
            if (p.length == 2) sym.put(p[1], Integer.parseInt(p[0], 16));
        }
        List<String> exp = Files.readAllLines(Paths.get(a[2]));
        m = new Specialist();
        m.loadRom(Files.readAllBytes(Paths.get(a[0])));
        m.hardReset();
        short[] au = new short[4000];
        for (int i = 0; i < 30; i++) m.runFrame(au);
        int[] twin = new int[256];
        for (int i = 0; i < 256; i++) twin[i] = i;
        for (int i : new int[]{0x08, 0x10, 0x18, 0x20, 0x28, 0x30, 0x38}) twin[i] = 0x00;
        twin[0xCB] = 0xC3; twin[0xD9] = 0xC9; twin[0xDD] = 0xCD; twin[0xED] = 0xCD; twin[0xFD] = 0xCD;
        int okDis = 0, okAsm = 0;
        for (int op = 0; op < 256; op++) {
            String[] e = exp.get(op).split(" ", 3);
            int len = Integer.parseInt(e[1]);
            String want = e[2];
            m.write(0x2000, op); m.write(0x2001, 0x34); m.write(0x2002, 0x12);
            setDE(0x2000); setHL(0x6000);
            call("DISASM");
            int end = m.cpu.hl();
            StringBuilder sb = new StringBuilder();
            for (int x = 0x6000; x < end; x++) sb.append(ch(m.peek(x)));
            String got = sb.toString();
            if (!got.equals(want) || m.cpu.b != len || m.cpu.de() != 0x2000 + len) {
                System.out.printf("FAIL dis %02X: \"%s\" len %d, want \"%s\" len %d%n", op, got, m.cpu.b, want, len);
                fails++;
            } else okDis++;
            // assemble the text back
            int[] code = asm(got);
            int[] wantBytes = len == 1 ? new int[]{twin[op]} : len == 2 ? new int[]{twin[op], 0x34} : new int[]{twin[op], 0x34, 0x12};
            if (!java.util.Arrays.equals(code, wantBytes)) {
                System.out.printf("FAIL asm \"%s\": %s, want %s%n", got, hex(code), hex(wantBytes));
                fails++;
            } else okAsm++;
        }
        System.out.printf("disassembled %d/256, assembled back %d/256%n", okDis, okAsm);
        check("mvi a,5", 0x3E, 0x05);
        check("jmp c800h", 0xC3, 0x00, 0xC8);
        check("  LXI  SP , 7FFF", 0x31, 0xFF, 0x7F);
        check("push psw", 0xF5);
        check("rst 7", 0xFF);
        check("mov m,a", 0x77);
        check("MVI A,H");
        check("MVI A,123");
        check("MOV A");
        check("XYZ 1");
        check("RST 8");
        check("INX A");
        check("NOP 1");
        System.out.println(fails == 0 ? "DISASM OK" : "DISASM FAILED: " + fails);
        if (fails > 0) System.exit(1);
    }

    static String hex(int[] b) {
        if (b == null) return "error";
        StringBuilder s = new StringBuilder();
        for (int v : b) s.append(String.format("%02X ", v));
        return s.toString().trim();
    }

    /** null = the assembler said the line is wrong */
    static int[] asm(String line) {
        for (int i = 0; i < line.length(); i++) m.write(0x6100 + i, code(line.charAt(i)));
        m.write(0x6100 + line.length(), 0);
        for (int i = 0; i < 4; i++) m.write(0x3000 + i, 0xEE);
        setDE(0x6100); setHL(0x3000);
        call("ASM1");
        if (m.cpu.fcy) return null;
        int n = m.cpu.b;
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = m.peek(0x3000 + i);
        return r;
    }

    static void check(String line, int... want) {
        int[] got = asm(line);
        boolean ok = want.length == 0 ? got == null : java.util.Arrays.equals(got, want);
        if (!ok) { System.out.printf("FAIL \"%s\": %s, want %s%n", line, hex(got), want.length == 0 ? "error" : hex(want)); fails++; }
        else System.out.printf("ok \"%s\" -> %s%n", line, hex(got));
    }

    /** keyboard codes: Latin small letters are A1..BA in the shell's character set */
    static int code(char c) {
        if (c >= 'a' && c <= 'z') return 0xA1 + (c - 'a');
        return c;
    }

    static char ch(int v) { return (char) v; }

    static void setDE(int v) { m.cpu.d = v >> 8; m.cpu.e = v & 0xFF; }
    static void setHL(int v) { m.cpu.h = v >> 8; m.cpu.l = v & 0xFF; }

    static void call(String name) {
        int ret = 0x0100;
        m.cpu.sp = 0x7F00 - 2;
        m.write(m.cpu.sp, ret & 0xFF);
        m.write(m.cpu.sp + 1, ret >> 8);
        m.cpu.pc = sym.get(name);
        long n = 0;
        while (m.cpu.pc != ret) { n += m.cpu.step(); if (n > 50_000_000) throw new IllegalStateException(name + " hangs"); }
    }
}
