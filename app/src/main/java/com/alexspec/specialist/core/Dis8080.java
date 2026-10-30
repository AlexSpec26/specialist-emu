package com.alexspec.specialist.core;

/**
 * One-line Intel 8080 (КР580ВМ80А) disassembler for the pause screen. Same mnemonics and
 * operand style as the ROM disassembler (shell/src/dis.asm, shell/tools/dis80.py): hex
 * without a suffix, "LXI B,1234", "MVI A,34". Undocumented duplicates are shown like the
 * documented instruction the 8080 executes for them (08 → NOP, CB → JMP, D9 → RET, DD/ED/FD → CALL).
 */
public final class Dis8080 {
    private static final String[] R = {"B", "C", "D", "E", "H", "L", "M", "A"};
    private static final String[] RP = {"B", "D", "H", "SP"};
    private static final String[] RP_PUSH = {"B", "D", "H", "PSW"};
    private static final String[] COND = {"NZ", "Z", "NC", "C", "PO", "PE", "P", "M"};
    private static final String[] ALU = {"ADD", "ADC", "SUB", "SBB", "ANA", "XRA", "ORA", "CMP"};
    private static final String[] ALU_I = {"ADI", "ACI", "SUI", "SBI", "ANI", "XRI", "ORI", "CPI"};
    private static final String[] ROT = {"RLC", "RRC", "RAL", "RAR", "DAA", "CMA", "STC", "CMC"};

    private Dis8080() {
    }

    /** Memory as the disassembler sees it. */
    public interface Mem {
        int rawRead(int addr);
    }

    /** length of the instruction with this opcode, 1..3 */
    public static int length(int op) {
        op &= 0xFF;
        if ((op & 0xC7) == 0x06 || (op & 0xC7) == 0xC6 || op == 0xD3 || op == 0xDB) return 2; // MVI, ALU#, OUT, IN
        if ((op & 0xCF) == 0x01) return 3;                                                     // LXI
        if (op == 0x22 || op == 0x2A || op == 0x32 || op == 0x3A) return 3;                    // SHLD LHLD STA LDA
        if ((op & 0xC7) == 0xC2 || (op & 0xC7) == 0xC4) return 3;                              // Jcc, Ccc
        if (op == 0xC3 || op == 0xCB || op == 0xCD || op == 0xDD || op == 0xED || op == 0xFD) return 3;
        return 1;
    }

    /** "MVI A,34" for the instruction at {@code pc}; its length goes to len[0] if given */
    public static String dis(Mem m, int pc, int[] len) {
        pc &= 0xFFFF;
        int op = m.rawRead(pc);
        int b = m.rawRead((pc + 1) & 0xFFFF), w = b | m.rawRead((pc + 2) & 0xFFFF) << 8;
        if (len != null) len[0] = length(op);
        return text(op, b, w);
    }

    /** the instruction text for an opcode and its operand byte / word */
    public static String text(int op, int b, int w) {
        String B = String.format("%02X", b & 0xFF), W = String.format("%04X", w & 0xFFFF);
        int x = op >> 6, y = (op >> 3) & 7, z = op & 7;
        switch (x) {
            case 1:
                return op == 0x76 ? "HLT" : "MOV " + R[y] + "," + R[z];
            case 2:
                return ALU[y] + " " + R[z];
            case 0:
                switch (z) {
                    case 0: return "NOP";
                    case 1: return (y & 1) == 0 ? "LXI " + RP[y >> 1] + "," + W : "DAD " + RP[y >> 1];
                    case 2: {
                        String[] t = {"STAX B", "LDAX B", "STAX D", "LDAX D", "SHLD " + W, "LHLD " + W, "STA " + W, "LDA " + W};
                        return t[y];
                    }
                    case 3: return ((y & 1) == 0 ? "INX " : "DCX ") + RP[y >> 1];
                    case 4: return "INR " + R[y];
                    case 5: return "DCR " + R[y];
                    case 6: return "MVI " + R[y] + "," + B;
                    default: return ROT[y];
                }
            default:
                switch (z) {
                    case 0: return "R" + COND[y];
                    case 1: {
                        if ((y & 1) == 0) return "POP " + RP_PUSH[y >> 1];
                        String[] t = {"RET", "RET", "PCHL", "SPHL"};
                        return t[y >> 1];
                    }
                    case 2: return "J" + COND[y] + " " + W;
                    case 3: {
                        String[] t = {"JMP " + W, "JMP " + W, "OUT " + B, "IN " + B, "XTHL", "XCHG", "DI", "EI"};
                        return t[y];
                    }
                    case 4: return "C" + COND[y] + " " + W;
                    case 5: return (y & 1) == 0 ? "PUSH " + RP_PUSH[y >> 1] : "CALL " + W;
                    case 6: return ALU_I[y] + " " + B;
                    default: return "RST " + y;
                }
        }
    }
}
