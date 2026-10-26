package com.alexspec.atari.core;

/**
 * One-line 6502 disassembler for the pause screen (current instruction and the last one).
 * The 151 documented opcodes; undocumented ones are shown as "???" with their likely length.
 */
public final class Dis6502 {
    private static final int IMP = 0, ACC = 1, IMM = 2, ZP = 3, ZPX = 4, ZPY = 5, ABS = 6, ABX = 7, ABY = 8,
            IND = 9, IZX = 10, IZY = 11, REL = 12;
    private static final int[] LEN = {1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 2, 2, 2};

    private static final String[] NAME = new String[256];
    private static final int[] MODE = new int[256];

    private static void op(int code, String name, int mode) {
        NAME[code] = name;
        MODE[code] = mode;
    }

    static {
        // undocumented: length guessed from the addressing-mode column of the opcode
        for (int c = 0; c < 256; c++) {
            NAME[c] = "???";
            int col = c & 0x1F;
            MODE[c] = col == 0x0C || col == 0x0E || col == 0x0F || col == 0x1C || col == 0x1E || col == 0x1F
                    || col == 0x1B || col == 0x19 ? ABS : (col == 0x02 || col == 0x12 || col == 0x1A || col == 0x0A) ? IMP : ZP;
        }
        String[] alu = {"ORA", "AND", "EOR", "ADC", "STA", "LDA", "CMP", "SBC"};
        int[] aluModes = {IZX, ZP, IMM, ABS, IZY, ZPX, ABY, ABX};
        int[] aluOffs = {0x01, 0x05, 0x09, 0x0D, 0x11, 0x15, 0x19, 0x1D};
        for (int g = 0; g < 8; g++) {
            for (int m = 0; m < 8; m++) {
                int code = g << 5 | aluOffs[m];
                if (code == 0x89) continue; // STA # does not exist
                op(code, alu[g], aluModes[m]);
            }
        }
        String[] sh = {"ASL", "ROL", "LSR", "ROR"};
        for (int g = 0; g < 4; g++) {
            int b = g << 5;
            op(b | 0x06, sh[g], ZP);
            op(b | 0x0A, sh[g], ACC);
            op(b | 0x0E, sh[g], ABS);
            op(b | 0x16, sh[g], ZPX);
            op(b | 0x1E, sh[g], ABX);
        }
        op(0x86, "STX", ZP); op(0x8E, "STX", ABS); op(0x96, "STX", ZPY);
        op(0xA2, "LDX", IMM); op(0xA6, "LDX", ZP); op(0xAE, "LDX", ABS); op(0xB6, "LDX", ZPY); op(0xBE, "LDX", ABY);
        op(0xC6, "DEC", ZP); op(0xCE, "DEC", ABS); op(0xD6, "DEC", ZPX); op(0xDE, "DEC", ABX);
        op(0xE6, "INC", ZP); op(0xEE, "INC", ABS); op(0xF6, "INC", ZPX); op(0xFE, "INC", ABX);
        op(0x84, "STY", ZP); op(0x8C, "STY", ABS); op(0x94, "STY", ZPX);
        op(0xA0, "LDY", IMM); op(0xA4, "LDY", ZP); op(0xAC, "LDY", ABS); op(0xB4, "LDY", ZPX); op(0xBC, "LDY", ABX);
        op(0xC0, "CPY", IMM); op(0xC4, "CPY", ZP); op(0xCC, "CPY", ABS);
        op(0xE0, "CPX", IMM); op(0xE4, "CPX", ZP); op(0xEC, "CPX", ABS);
        op(0x24, "BIT", ZP); op(0x2C, "BIT", ABS);
        op(0x4C, "JMP", ABS); op(0x6C, "JMP", IND); op(0x20, "JSR", ABS);
        String[] br = {"BPL", "BMI", "BVC", "BVS", "BCC", "BCS", "BNE", "BEQ"};
        for (int i = 0; i < 8; i++) op(0x10 + i * 0x20, br[i], REL);
        Object[][] imp = {
            {0x00, "BRK"}, {0x40, "RTI"}, {0x60, "RTS"}, {0x08, "PHP"}, {0x28, "PLP"}, {0x48, "PHA"}, {0x68, "PLA"},
            {0x88, "DEY"}, {0xA8, "TAY"}, {0xC8, "INY"}, {0xE8, "INX"}, {0x18, "CLC"}, {0x38, "SEC"}, {0x58, "CLI"},
            {0x78, "SEI"}, {0x98, "TYA"}, {0xB8, "CLV"}, {0xD8, "CLD"}, {0xF8, "SED"}, {0x8A, "TXA"}, {0x9A, "TXS"},
            {0xAA, "TAX"}, {0xBA, "TSX"}, {0xCA, "DEX"}, {0xEA, "NOP"},
        };
        for (Object[] o : imp) op((Integer) o[0], (String) o[1], IMP);
    }

    private Dis6502() {
    }

    /** length of the instruction at {@code pc} in bytes */
    public static int length(MemTrace.Mem m, int pc) {
        return LEN[MODE[m.rawRead(pc & 0xFFFF)]];
    }

    /** "LDA $D40B,X" for the instruction at {@code pc}; its length goes to len[0] if given */
    public static String dis(MemTrace.Mem m, int pc, int[] len) {
        pc &= 0xFFFF;
        int op = m.rawRead(pc), mode = MODE[op];
        int b1 = m.rawRead(pc + 1), w = b1 | m.rawRead(pc + 2) << 8;
        if (len != null) len[0] = LEN[mode];
        String n = NAME[op];
        switch (mode) {
            case ACC: return n + " A";
            case IMM: return String.format("%s #$%02X", n, b1);
            case ZP: return String.format("%s $%02X", n, b1);
            case ZPX: return String.format("%s $%02X,X", n, b1);
            case ZPY: return String.format("%s $%02X,Y", n, b1);
            case ABS: return String.format("%s $%04X", n, w);
            case ABX: return String.format("%s $%04X,X", n, w);
            case ABY: return String.format("%s $%04X,Y", n, w);
            case IND: return String.format("%s ($%04X)", n, w);
            case IZX: return String.format("%s ($%02X,X)", n, b1);
            case IZY: return String.format("%s ($%02X),Y", n, b1);
            case REL: return String.format("%s $%04X", n, (pc + 2 + (byte) b1) & 0xFFFF);
            default: return n;
        }
    }
}
