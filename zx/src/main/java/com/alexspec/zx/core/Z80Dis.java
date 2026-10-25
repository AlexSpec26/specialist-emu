package com.alexspec.zx.core;

/** One-line Z80 disassembler (all documented and the common undocumented forms). */
public final class Z80Dis {
    private static final String[] R = {"B", "C", "D", "E", "H", "L", "(HL)", "A"};
    private static final String[] RP = {"BC", "DE", "HL", "SP"};
    private static final String[] RP2 = {"BC", "DE", "HL", "AF"};
    private static final String[] CC = {"NZ", "Z", "NC", "C", "PO", "PE", "P", "M"};
    private static final String[] ALU = {"ADD A,", "ADC A,", "SUB ", "SBC A,", "AND ", "XOR ", "OR ", "CP "};
    private static final String[] ROT = {"RLC", "RRC", "RL", "RR", "SLA", "SRA", "SLL", "SRL"};
    private static final String[] IM = {"0", "0", "1", "2", "0", "0", "1", "2"};
    private static final String[][] BLI = {
        {"LDI", "CPI", "INI", "OUTI"}, {"LDD", "CPD", "IND", "OUTD"},
        {"LDIR", "CPIR", "INIR", "OTIR"}, {"LDDR", "CPDR", "INDR", "OTDR"}};

    private final MemTrace.Mem m;
    private int pc, start;
    private String ix; // "HL", "IX" or "IY"

    private Z80Dis(MemTrace.Mem m, int pc) {
        this.m = m;
        this.pc = pc & 0xFFFF;
        this.start = this.pc;
    }

    private int b() {
        int v = m.rawRead(pc);
        pc = (pc + 1) & 0xFFFF;
        return v;
    }

    private String n() {
        return String.format("%02X", b());
    }

    private String nn() {
        int lo = b();
        return String.format("%04X", lo | (b() << 8));
    }

    private String disp(int d) {
        d = (byte) d;
        return d < 0 ? "-" + String.format("%02X", -d) : "+" + String.format("%02X", d);
    }

    /** register name with IX/IY substitution; (HL) becomes (IX+d) */
    private String r(int i, int[] d) {
        if (ix.equals("HL")) return R[i];
        if (i == 6) {
            if (d[0] == Integer.MIN_VALUE) d[0] = b();
            return "(" + ix + disp(d[0]) + ")";
        }
        if (i == 4) return ix + "H";
        if (i == 5) return ix + "L";
        return R[i];
    }

    private String rp(int p) {
        return p == 2 ? ix : RP[p];
    }

    private String rp2(int p) {
        return p == 2 ? ix : RP2[p];
    }

    /** Disassembles the instruction at {@code pc}; len[0] receives its length. */
    public static String dis(MemTrace.Mem m, int pc, int[] len) {
        Z80Dis d = new Z80Dis(m, pc);
        String s = d.decode();
        if (len != null) len[0] = (d.pc - d.start) & 0xFFFF;
        return s;
    }

    private String decode() {
        ix = "HL";
        int op = b();
        while (op == 0xDD || op == 0xFD) {
            ix = op == 0xDD ? "IX" : "IY";
            op = b();
        }
        if (op == 0xED) return ed(b());
        if (op == 0xCB) return cb();
        int x = op >> 6, y = (op >> 3) & 7, z = op & 7, p = y >> 1, q = y & 1;
        int[] d = {Integer.MIN_VALUE};
        switch (x) {
            case 0:
                switch (z) {
                    case 0:
                        if (y == 0) return "NOP";
                        if (y == 1) return "EX AF,AF'";
                        if (y == 2) return "DJNZ " + rel();
                        if (y == 3) return "JR " + rel();
                        return "JR " + CC[y - 4] + "," + rel();
                    case 1:
                        return q == 0 ? "LD " + rp(p) + "," + nn() : "ADD " + ix + "," + rp(p);
                    case 2: {
                        String[] t = {"LD (BC),A", "LD A,(BC)", "LD (DE),A", "LD A,(DE)"};
                        if (p < 2) return t[p * 2 + q];
                        if (p == 2) return q == 0 ? "LD (" + nn() + ")," + ix : "LD " + ix + ",(" + nn() + ")";
                        return q == 0 ? "LD (" + nn() + "),A" : "LD A,(" + nn() + ")";
                    }
                    case 3:
                        return (q == 0 ? "INC " : "DEC ") + rp(p);
                    case 4:
                        return "INC " + r(y, d);
                    case 5:
                        return "DEC " + r(y, d);
                    case 6: {
                        String reg = r(y, d);
                        return "LD " + reg + "," + n();
                    }
                    default: {
                        String[] t = {"RLCA", "RRCA", "RLA", "RRA", "DAA", "CPL", "SCF", "CCF"};
                        return t[y];
                    }
                }
            case 1:
                if (y == 6 && z == 6) return "HALT";
                // LD (IX+d),H keeps plain H; LD H,(IX+d) too
                if (z == 6 && !ix.equals("HL")) return "LD " + R[y] + "," + r(6, d);
                if (y == 6 && !ix.equals("HL")) return "LD " + r(6, d) + "," + R[z];
                return "LD " + r(y, d) + "," + r(z, d);
            case 2:
                return ALU[y] + r(z, d);
            default:
                switch (z) {
                    case 0: return "RET " + CC[y];
                    case 1:
                        if (q == 0) return "POP " + rp2(p);
                        if (p == 0) return "RET";
                        if (p == 1) return "EXX";
                        if (p == 2) return "JP (" + ix + ")";
                        return "LD SP," + ix;
                    case 2: return "JP " + CC[y] + "," + nn();
                    case 3:
                        switch (y) {
                            case 0: return "JP " + nn();
                            case 2: return "OUT (" + n() + "),A";
                            case 3: return "IN A,(" + n() + ")";
                            case 4: return "EX (SP)," + ix;
                            case 5: return "EX DE,HL";
                            case 6: return "DI";
                            case 7: return "EI";
                            default: return "?";
                        }
                    case 4: return "CALL " + CC[y] + "," + nn();
                    case 5:
                        if (q == 0) return "PUSH " + rp2(p);
                        return "CALL " + nn();
                    case 6: return ALU[y] + n();
                    default: return String.format("RST %02X", y * 8);
                }
        }
    }

    private String rel() {
        int e = (byte) b();
        return String.format("%04X", (pc + e) & 0xFFFF);
    }

    private String cb() {
        int dd = 0;
        if (!ix.equals("HL")) dd = b();
        int op = b();
        int x = op >> 6, y = (op >> 3) & 7, z = op & 7;
        String target = ix.equals("HL") ? R[z] : "(" + ix + disp(dd) + ")";
        String extra = !ix.equals("HL") && z != 6 ? "," + R[z] : "";
        if (x == 0) return ROT[y] + " " + target + extra;
        if (x == 1) return "BIT " + y + "," + target;
        return (x == 2 ? "RES " : "SET ") + y + "," + target + extra;
    }

    private String ed(int op) {
        int x = op >> 6, y = (op >> 3) & 7, z = op & 7, p = y >> 1, q = y & 1;
        if (x == 1) {
            switch (z) {
                case 0: return y == 6 ? "IN (C)" : "IN " + R[y] + ",(C)";
                case 1: return y == 6 ? "OUT (C),0" : "OUT (C)," + R[y];
                case 2: return (q == 0 ? "SBC HL," : "ADC HL,") + RP[p];
                case 3: return q == 0 ? "LD (" + nn() + ")," + RP[p] : "LD " + RP[p] + ",(" + nn() + ")";
                case 4: return "NEG";
                case 5: return y == 1 ? "RETI" : "RETN";
                case 6: return "IM " + IM[y];
                default: {
                    String[] t = {"LD I,A", "LD R,A", "LD A,I", "LD A,R", "RRD", "RLD", "NOP*", "NOP*"};
                    return t[y];
                }
            }
        }
        if (x == 2 && y >= 4 && z <= 3) return BLI[y - 4][z];
        return String.format("NOP* (ED %02X)", op);
    }
}
