package com.alexspec.zx.core;

/**
 * Zilog Z80 interpreter with undocumented flags (bits 3/5), MEMPTR, and FUSE-compatible
 * timing: every memory access, every "no-MREQ" internal cycle and every port access goes
 * through the contention hooks at the same T-state as on a real Spectrum, so the ULA
 * contention pattern comes out right.
 */
public final class Z80 {
    /** Memory/IO and contention provided by the machine. */
    public interface Bus {
        int read(int addr);

        void write(int addr, int value);

        int in(int port);

        void out(int port, int value);

        /** opcode fetch (M1); default = normal read */
        default int fetch(int addr) {
            return read(addr);
        }

        /** instruction operand read (immediate, displacement); default = normal read */
        default int arg(int addr) {
            return read(addr);
        }

        /** operand byte of a branch not taken: on the bus but not used (for tracing only) */
        default void skipped(int addr) {
        }

        /** is this memory address subject to ULA contention */
        boolean contended(int addr);

        /** extra T-states the ULA inserts at time t (only asked when contention applies) */
        int delay(long t);
    }

    /** Optional event listener (tests). */
    public interface Listener {
        void event(long t, String type, int addr, int data);
    }

    // flags
    public static final int FC = 0x01, FN = 0x02, FP = 0x04, FV = 0x04, F3 = 0x08, FH = 0x10, F5 = 0x20, FZ = 0x40, FS = 0x80;

    private static final int[] SZ53 = new int[256], SZ53P = new int[256], PAR = new int[256];
    private static final int[] HC_ADD = {0, FH, FH, FH, 0, 0, 0, FH};
    private static final int[] HC_SUB = {0, 0, FH, 0, FH, 0, FH, FH};
    private static final int[] OV_ADD = {0, 0, 0, FV, FV, 0, 0, 0};
    private static final int[] OV_SUB = {0, FV, 0, 0, 0, 0, FV, 0};

    static {
        for (int i = 0; i < 256; i++) {
            SZ53[i] = i & (F3 | F5 | FS);
            int p = 0;
            for (int j = i, k = 0; k < 8; k++, j >>= 1) p ^= j & 1;
            PAR[i] = p == 0 ? FP : 0;
            SZ53P[i] = SZ53[i] | PAR[i];
        }
        SZ53[0] |= FZ;
        SZ53P[0] |= FZ;
    }

    private final Bus bus;
    public Listener listener;

    public int a, f, b, c, d, e, h, l;
    public int a_, f_, b_, c_, d_, e_, h_, l_;
    public int ix, iy, sp, pc, i, r, r7, memptr;
    public boolean iff1, iff2, halted;
    public int im;
    /** T-states since the start of the frame */
    public long t;
    /** set by EI: interrupts are not accepted until after the next instruction */
    boolean eiDelay; // package access: machine snapshots
    /** last instruction was a DD/FD prefixed one (for the interrupt check after prefixes) */

    public Z80(Bus bus) {
        this.bus = bus;
    }

    public void reset() {
        a = f = 0xFF;
        b = c = d = e = h = l = 0;
        sp = 0xFFFF;
        pc = 0;
        i = r = r7 = 0;
        ix = iy = 0;
        memptr = 0;
        iff1 = iff2 = false;
        im = 0;
        halted = false;
        eiDelay = false;
    }

    // ------------------------------------------------------------------ registers

    public int bc() { return (b << 8) | c; }
    public int de() { return (d << 8) | e; }
    public int hl() { return (h << 8) | l; }
    public int af() { return (a << 8) | f; }
    public int ir() { return (i << 8) | ((r & 0x7F) | r7); }

    public void setBC(int v) { b = (v >> 8) & 0xFF; c = v & 0xFF; }
    public void setDE(int v) { d = (v >> 8) & 0xFF; e = v & 0xFF; }
    public void setHL(int v) { h = (v >> 8) & 0xFF; l = v & 0xFF; }
    public void setAF(int v) { a = (v >> 8) & 0xFF; f = v & 0xFF; }
    public int getR() { return (r & 0x7F) | r7; }
    public void setR(int v) { r = v & 0x7F; r7 = v & 0x80; }

    // ------------------------------------------------------------------ bus with timing

    private void contend(int addr) {
        if (listener != null) listener.event(t, "MC", addr, -1);
        if (bus.contended(addr)) t += bus.delay(t);
    }

    /** internal cycles that put an address on the bus without MREQ (contended on the 48K) */
    private void noMreq(int addr, int n) {
        for (int k = 0; k < n; k++) {
            contend(addr);
            t++;
        }
    }

    private int fetchOp() {
        contend(pc);
        t += 4;
        int v = bus.fetch(pc);
        if (listener != null) listener.event(t, "MR", pc, v);
        pc = (pc + 1) & 0xFFFF;
        r = (r + 1) & 0x7F;
        return v;
    }

    private int rd(int addr) {
        contend(addr);
        t += 3;
        int v = bus.read(addr);
        if (listener != null) listener.event(t, "MR", addr, v);
        return v;
    }

    private void wr(int addr, int v) {
        contend(addr);
        t += 3;
        if (listener != null) listener.event(t, "MW", addr, v);
        bus.write(addr, v);
    }

    /** operand cycle of a branch not taken: the bus is busy (contended) but nothing is used */
    private void skip() {
        contend(pc);
        t += 3;
        bus.skipped(pc);
        pc = (pc + 1) & 0xFFFF;
    }

    private int rdArg(int addr) {
        contend(addr);
        t += 3;
        int v = bus.arg(addr);
        if (listener != null) listener.event(t, "MR", addr, v);
        return v;
    }

    private int imm() {
        int v = rdArg(pc);
        pc = (pc + 1) & 0xFFFF;
        return v;
    }

    private int imm16() {
        int lo = imm();
        return lo | (imm() << 8);
    }

    private void portEarly(int port) {
        if ((port & 0xC000) == 0x4000) {
            if (listener != null) listener.event(t, "PC", port, -1);
            t += bus.delay(t);
        }
        t++;
    }

    private void portLate(int port) {
        if ((port & 1) == 0) {
            if (listener != null) listener.event(t, "PC", port, -1);
            t += bus.delay(t);
            t += 3;
        } else if ((port & 0xC000) == 0x4000) {
            for (int k = 0; k < 3; k++) {
                if (listener != null) listener.event(t, "PC", port, -1);
                t += bus.delay(t);
                t++;
            }
        } else {
            t += 3;
        }
    }

    private int portIn(int port) {
        portEarly(port);
        int v = bus.in(port) & 0xFF;
        if (listener != null) listener.event(t, "PR", port, v);
        portLate(port);
        return v;
    }

    private void portOut(int port, int v) {
        portEarly(port);
        if (listener != null) listener.event(t, "PW", port, v);
        bus.out(port, v);
        portLate(port);
    }

    private void push(int v) {
        sp = (sp - 1) & 0xFFFF;
        wr(sp, (v >> 8) & 0xFF);
        sp = (sp - 1) & 0xFFFF;
        wr(sp, v & 0xFF);
    }

    private int pop() {
        int lo = rd(sp);
        sp = (sp + 1) & 0xFFFF;
        int hi = rd(sp);
        sp = (sp + 1) & 0xFFFF;
        return lo | (hi << 8);
    }

    // ------------------------------------------------------------------ ALU

    private void add(int v) {
        int tmp = a + v;
        int lk = ((a & 0x88) >> 3) | ((v & 0x88) >> 2) | ((tmp & 0x88) >> 1);
        a = tmp & 0xFF;
        f = ((tmp & 0x100) != 0 ? FC : 0) | HC_ADD[lk & 7] | OV_ADD[lk >> 4] | SZ53[a];
    }

    private void adc(int v) {
        int tmp = a + v + (f & FC);
        int lk = ((a & 0x88) >> 3) | ((v & 0x88) >> 2) | ((tmp & 0x88) >> 1);
        a = tmp & 0xFF;
        f = ((tmp & 0x100) != 0 ? FC : 0) | HC_ADD[lk & 7] | OV_ADD[lk >> 4] | SZ53[a];
    }

    private void sub(int v) {
        int tmp = a - v;
        int lk = ((a & 0x88) >> 3) | ((v & 0x88) >> 2) | ((tmp & 0x88) >> 1);
        a = tmp & 0xFF;
        f = ((tmp & 0x100) != 0 ? FC : 0) | FN | HC_SUB[lk & 7] | OV_SUB[lk >> 4] | SZ53[a];
    }

    private void sbc(int v) {
        int tmp = a - v - (f & FC);
        int lk = ((a & 0x88) >> 3) | ((v & 0x88) >> 2) | ((tmp & 0x88) >> 1);
        a = tmp & 0xFF;
        f = ((tmp & 0x100) != 0 ? FC : 0) | FN | HC_SUB[lk & 7] | OV_SUB[lk >> 4] | SZ53[a];
    }

    private void and(int v) {
        a &= v;
        f = FH | SZ53P[a];
    }

    private void xor(int v) {
        a = (a ^ v) & 0xFF;
        f = SZ53P[a];
    }

    private void or(int v) {
        a = (a | v) & 0xFF;
        f = SZ53P[a];
    }

    private void cp(int v) {
        int tmp = a - v;
        int lk = ((a & 0x88) >> 3) | ((v & 0x88) >> 2) | ((tmp & 0x88) >> 1);
        f = ((tmp & 0x100) != 0 ? FC : ((tmp & 0xFF) == 0 ? FZ : 0)) | FN | HC_SUB[lk & 7] | OV_SUB[lk >> 4]
                | (v & (F3 | F5)) | (tmp & FS);
        if ((tmp & 0xFF) == 0 && (tmp & 0x100) != 0) f |= FZ;
    }

    private void alu(int op, int v) {
        switch (op) {
            case 0: add(v); break;
            case 1: adc(v); break;
            case 2: sub(v); break;
            case 3: sbc(v); break;
            case 4: and(v); break;
            case 5: xor(v); break;
            case 6: or(v); break;
            default: cp(v); break;
        }
    }

    private int inc(int v) {
        v = (v + 1) & 0xFF;
        f = (f & FC) | (v == 0x80 ? FV : 0) | ((v & 0x0F) != 0 ? 0 : FH) | SZ53[v];
        return v;
    }

    private int dec(int v) {
        f = (f & FC) | ((v & 0x0F) != 0 ? 0 : FH) | FN;
        v = (v - 1) & 0xFF;
        f |= (v == 0x7F ? FV : 0) | SZ53[v];
        return v;
    }

    private int add16(int x, int v) {
        int tmp = x + v;
        int lk = ((x & 0x0800) >> 11) | ((v & 0x0800) >> 10) | ((tmp & 0x0800) >> 9);
        memptr = (x + 1) & 0xFFFF;
        f = (f & (FV | FZ | FS)) | ((tmp & 0x10000) != 0 ? FC : 0) | ((tmp >> 8) & (F3 | F5)) | HC_ADD[lk];
        return tmp & 0xFFFF;
    }

    private void adc16(int v) {
        int x = hl();
        int tmp = x + v + (f & FC);
        int lk = ((x & 0x8800) >> 11) | ((v & 0x8800) >> 10) | ((tmp & 0x8800) >> 9);
        memptr = (x + 1) & 0xFFFF;
        setHL(tmp);
        int nh = tmp & 0xFFFF;
        f = ((tmp & 0x10000) != 0 ? FC : 0) | OV_ADD[lk >> 4] | ((nh >> 8) & (F3 | F5 | FS)) | HC_ADD[lk & 7]
                | (nh != 0 ? 0 : FZ);
    }

    private void sbc16(int v) {
        int x = hl();
        int tmp = x - v - (f & FC);
        int lk = ((x & 0x8800) >> 11) | ((v & 0x8800) >> 10) | ((tmp & 0x8800) >> 9);
        memptr = (x + 1) & 0xFFFF;
        setHL(tmp);
        int nh = tmp & 0xFFFF;
        f = ((tmp & 0x10000) != 0 ? FC : 0) | FN | OV_SUB[lk >> 4] | ((nh >> 8) & (F3 | F5 | FS)) | HC_SUB[lk & 7]
                | (nh != 0 ? 0 : FZ);
    }

    private int rot(int op, int v) {
        switch (op) {
            case 0: // RLC
                v = ((v << 1) | (v >> 7)) & 0xFF;
                f = (v & FC) | SZ53P[v];
                return v;
            case 1: // RRC
                f = v & FC;
                v = ((v >> 1) | (v << 7)) & 0xFF;
                f |= SZ53P[v];
                return v;
            case 2: { // RL
                int o = v;
                v = ((v << 1) | (f & FC)) & 0xFF;
                f = (o >> 7) | SZ53P[v];
                return v;
            }
            case 3: { // RR
                int o = v;
                v = ((v >> 1) | (f << 7)) & 0xFF;
                f = (o & FC) | SZ53P[v];
                return v;
            }
            case 4: // SLA
                f = v >> 7;
                v = (v << 1) & 0xFF;
                f |= SZ53P[v];
                return v;
            case 5: // SRA
                f = v & FC;
                v = (v & 0x80) | (v >> 1);
                f |= SZ53P[v];
                return v;
            case 6: // SLL (undocumented)
                f = v >> 7;
                v = ((v << 1) | 1) & 0xFF;
                f |= SZ53P[v];
                return v;
            default: // SRL
                f = v & FC;
                v >>= 1;
                f |= SZ53P[v];
                return v;
        }
    }

    private void bit(int n, int v, int xy) {
        f = (f & FC) | FH | (xy & (F3 | F5));
        if ((v & (1 << n)) == 0) f |= FP | FZ;
        if (n == 7 && (v & 0x80) != 0) f |= FS;
    }

    private boolean cond(int cc) {
        switch (cc) {
            case 0: return (f & FZ) == 0;
            case 1: return (f & FZ) != 0;
            case 2: return (f & FC) == 0;
            case 3: return (f & FC) != 0;
            case 4: return (f & FP) == 0;
            case 5: return (f & FP) != 0;
            case 6: return (f & FS) == 0;
            default: return (f & FS) != 0;
        }
    }

    // ------------------------------------------------------------------ register access by index

    /** r: 0 B, 1 C, 2 D, 3 E, 4 H, 5 L, 7 A (6 = memory, handled by caller) */
    private int getR8(int idx) {
        switch (idx) {
            case 0: return b;
            case 1: return c;
            case 2: return d;
            case 3: return e;
            case 4: return h;
            case 5: return l;
            default: return a;
        }
    }

    private void setR8(int idx, int v) {
        switch (idx) {
            case 0: b = v; break;
            case 1: c = v; break;
            case 2: d = v; break;
            case 3: e = v; break;
            case 4: h = v; break;
            case 5: l = v; break;
            default: a = v; break;
        }
    }

    /** same, with H/L replaced by the high/low byte of an index register (prefix 1 = IX, 2 = IY) */
    private int getR8x(int idx, int px) {
        if (px != 0 && (idx == 4 || idx == 5)) {
            int x = px == 1 ? ix : iy;
            return idx == 4 ? x >> 8 : x & 0xFF;
        }
        return getR8(idx);
    }

    private void setR8x(int idx, int v, int px) {
        if (px != 0 && (idx == 4 || idx == 5)) {
            if (px == 1) ix = idx == 4 ? (v << 8) | (ix & 0xFF) : (ix & 0xFF00) | v;
            else iy = idx == 4 ? (v << 8) | (iy & 0xFF) : (iy & 0xFF00) | v;
            return;
        }
        setR8(idx, v);
    }

    private int getRP(int p, int px) {
        switch (p) {
            case 0: return bc();
            case 1: return de();
            case 2: return px == 0 ? hl() : px == 1 ? ix : iy;
            default: return sp;
        }
    }

    private void setRP(int p, int v, int px) {
        v &= 0xFFFF;
        switch (p) {
            case 0: setBC(v); break;
            case 1: setDE(v); break;
            case 2:
                if (px == 0) setHL(v);
                else if (px == 1) ix = v;
                else iy = v;
                break;
            default: sp = v; break;
        }
    }

    private int getRP2(int p, int px) {
        return p == 3 ? af() : getRP(p, px);
    }

    private void setRP2(int p, int v, int px) {
        if (p == 3) setAF(v);
        else setRP(p, v, px);
    }

    // ------------------------------------------------------------------ execution

    /**
     * Accepts a maskable interrupt if enabled (call at the start of the frame while the INT
     * line is active). Returns true if it was taken.
     */
    public boolean interrupt() {
        if (!iff1 || eiDelay) return false;
        if (halted) {
            halted = false;
            pc = (pc + 1) & 0xFFFF;
        }
        iff1 = iff2 = false;
        r = (r + 1) & 0x7F;
        if (im == 2) {
            t += 7;
            push(pc);
            int vec = (i << 8) | 0xFF;
            int lo = rd(vec);
            int hi = rd((vec + 1) & 0xFFFF);
            pc = lo | (hi << 8);
        } else {
            t += 7;
            push(pc);
            pc = 0x38;
        }
        memptr = pc;
        return true;
    }

    /** Executes one instruction (prefixes included). */
    public void step() {
        eiDelay = false;
        int op = fetchOp();
        if (halted) {
            // HALT re-executes itself (a NOP with PC held) until an interrupt
            pc = (pc - 1) & 0xFFFF;
            return;
        }
        execute(op, 0);
    }

    private void execute(int op, int px) {
        switch (op) {
            case 0xCB:
                if (px == 0) execCB();
                else execXYCB(px);
                return;
            case 0xED:
                execED(fetchOp());
                return;
            case 0xDD:
                execute(fetchOp(), 1);
                return;
            case 0xFD:
                execute(fetchOp(), 2);
                return;
            default:
                break;
        }

        int x = op >> 6, y = (op >> 3) & 7, z = op & 7, p = y >> 1, q = y & 1;
        if (x == 1) {
            if (op == 0x76) {
                halted = true;
                pc = (pc - 1) & 0xFFFF;
                return;
            }
            if (y == 6) {
                // LD (HL|IX+d), r  (r uses real H/L)
                int addr = memAddr(px);
                wr(addr, getR8(z));
            } else if (z == 6) {
                int addr = memAddr(px);
                setR8(y, rd(addr));
            } else {
                setR8x(y, getR8x(z, px), px);
            }
            return;
        }
        if (x == 2) {
            int v = z == 6 ? rd(memAddr(px)) : getR8x(z, px);
            alu(y, v);
            return;
        }
        if (x == 0) {
            switch (z) {
                case 0:
                    switch (y) {
                        case 0: return; // NOP
                        case 1: { // EX AF,AF'
                            int ta = a, tf = f;
                            a = a_;
                            f = f_;
                            a_ = ta;
                            f_ = tf;
                            return;
                        }
                        case 2: { // DJNZ
                            noMreq(ir(), 1);
                            b = (b - 1) & 0xFF;
                            if (b != 0) jr();
                            else skip();
                            return;
                        }
                        case 3:
                            jr();
                            return;
                        default:
                            if (cond(y - 4)) jr();
                            else skip();
                            return;
                    }
                case 1:
                    if (q == 0) setRP(p, imm16(), px);
                    else {
                        noMreq(ir(), 7);
                        int hlx = getRP(2, px);
                        setRP(2, add16(hlx, getRP(p, px)), px);
                    }
                    return;
                case 2:
                    switch (y) {
                        case 0: // LD (BC),A
                            wr(bc(), a);
                            memptr = ((bc() + 1) & 0xFF) | (a << 8);
                            return;
                        case 1: // LD A,(BC)
                            memptr = (bc() + 1) & 0xFFFF;
                            a = rd(bc());
                            return;
                        case 2: // LD (DE),A
                            wr(de(), a);
                            memptr = ((de() + 1) & 0xFF) | (a << 8);
                            return;
                        case 3:
                            memptr = (de() + 1) & 0xFFFF;
                            a = rd(de());
                            return;
                        case 4: { // LD (nn),HL
                            int nn = imm16();
                            int v = getRP(2, px);
                            wr(nn, v & 0xFF);
                            memptr = (nn + 1) & 0xFFFF;
                            wr(memptr, v >> 8);
                            return;
                        }
                        case 5: { // LD HL,(nn)
                            int nn = imm16();
                            int lo = rd(nn);
                            memptr = (nn + 1) & 0xFFFF;
                            int hi = rd(memptr);
                            setRP(2, lo | (hi << 8), px);
                            return;
                        }
                        case 6: { // LD (nn),A
                            int nn = imm16();
                            memptr = ((nn + 1) & 0xFF) | (a << 8);
                            wr(nn, a);
                            return;
                        }
                        default: { // LD A,(nn)
                            int nn = imm16();
                            memptr = (nn + 1) & 0xFFFF;
                            a = rd(nn);
                            return;
                        }
                    }
                case 3:
                    noMreq(ir(), 2);
                    setRP(p, getRP(p, px) + (q == 0 ? 1 : -1), px);
                    return;
                case 4:
                    if (y == 6) {
                        int addr = memAddr5(px);
                        int v = rd(addr);
                        noMreq(addr, 1);
                        wr(addr, inc(v));
                    } else setR8x(y, inc(getR8x(y, px)), px);
                    return;
                case 5:
                    if (y == 6) {
                        int addr = memAddr5(px);
                        int v = rd(addr);
                        noMreq(addr, 1);
                        wr(addr, dec(v));
                    } else setR8x(y, dec(getR8x(y, px)), px);
                    return;
                case 6:
                    if (y == 6) {
                        if (px == 0) {
                            int n = imm();
                            wr(hl(), n);
                        } else {
                            int dd = (byte) rdArg(pc);
                            pc = (pc + 1) & 0xFFFF;
                            int n = rdArg(pc);
                            noMreq(pc, 2);
                            pc = (pc + 1) & 0xFFFF;
                            int addr = ((px == 1 ? ix : iy) + dd) & 0xFFFF;
                            memptr = addr;
                            wr(addr, n);
                        }
                    } else setR8x(y, imm(), px);
                    return;
                default:
                    switch (y) {
                        case 0: // RLCA
                            a = ((a << 1) | (a >> 7)) & 0xFF;
                            f = (f & (FP | FZ | FS)) | (a & (FC | F3 | F5));
                            return;
                        case 1: // RRCA
                            f = (f & (FP | FZ | FS)) | (a & FC);
                            a = ((a >> 1) | (a << 7)) & 0xFF;
                            f |= a & (F3 | F5);
                            return;
                        case 2: { // RLA
                            int o = a;
                            a = ((a << 1) | (f & FC)) & 0xFF;
                            f = (f & (FP | FZ | FS)) | (a & (F3 | F5)) | (o >> 7);
                            return;
                        }
                        case 3: { // RRA
                            int o = a;
                            a = ((a >> 1) | (f << 7)) & 0xFF;
                            f = (f & (FP | FZ | FS)) | (a & (F3 | F5)) | (o & FC);
                            return;
                        }
                        case 4:
                            daa();
                            return;
                        case 5: // CPL
                            a ^= 0xFF;
                            f = (f & (FC | FP | FZ | FS)) | (a & (F3 | F5)) | FN | FH;
                            return;
                        case 6: // SCF
                            f = (f & (FP | FZ | FS)) | (a & (F3 | F5)) | FC;
                            return;
                        default: // CCF
                            f = (f & (FP | FZ | FS)) | ((f & FC) != 0 ? FH : FC) | (a & (F3 | F5));
                            return;
                    }
            }
        }
        // x == 3
        switch (z) {
            case 0: // RET cc
                noMreq(ir(), 1);
                if (cond(y)) {
                    pc = pop();
                    memptr = pc;
                }
                return;
            case 1:
                if (q == 0) {
                    setRP2(p, pop(), px);
                } else {
                    switch (p) {
                        case 0: // RET
                            pc = pop();
                            memptr = pc;
                            return;
                        case 1: { // EXX
                            int t1 = b, t2 = c, t3 = d, t4 = e, t5 = h, t6 = l;
                            b = b_; c = c_; d = d_; e = e_; h = h_; l = l_;
                            b_ = t1; c_ = t2; d_ = t3; e_ = t4; h_ = t5; l_ = t6;
                            return;
                        }
                        case 2: // JP (HL)
                            pc = getRP(2, px);
                            return;
                        default: // LD SP,HL
                            noMreq(ir(), 2);
                            sp = getRP(2, px);
                            return;
                    }
                }
                return;
            case 2: // JP cc,nn
                if (cond(y)) {
                    int nn = imm16();
                    memptr = nn;
                    pc = nn;
                } else {
                    skip();
                    skip();
                }
                return;
            case 3:
                switch (y) {
                    case 0: { // JP nn
                        int nn = imm16();
                        memptr = nn;
                        pc = nn;
                        return;
                    }
                    case 2: { // OUT (n),A
                        int n = imm();
                        int port = n | (a << 8);
                        portOut(port, a);
                        memptr = ((n + 1) & 0xFF) | (a << 8);
                        return;
                    }
                    case 3: { // IN A,(n)
                        int n = imm();
                        int port = n | (a << 8);
                        memptr = (port + 1) & 0xFFFF;
                        a = portIn(port);
                        return;
                    }
                    case 4: { // EX (SP),HL
                        int lo = rd(sp);
                        int hi = rd((sp + 1) & 0xFFFF);
                        noMreq((sp + 1) & 0xFFFF, 1);
                        int v = getRP(2, px);
                        wr((sp + 1) & 0xFFFF, v >> 8);
                        wr(sp, v & 0xFF);
                        noMreq(sp, 2);
                        int nv = lo | (hi << 8);
                        setRP(2, nv, px);
                        memptr = nv;
                        return;
                    }
                    case 5: { // EX DE,HL (never affected by prefixes)
                        int tmp = de();
                        setDE(hl());
                        setHL(tmp);
                        return;
                    }
                    case 6: // DI
                        iff1 = iff2 = false;
                        return;
                    default: // EI
                        iff1 = iff2 = true;
                        eiDelay = true;
                        return;
                }
            case 4: // CALL cc,nn
                if (cond(y)) {
                    int lo = imm();
                    int hi = rdArg(pc);
                    int nn = lo | (hi << 8);
                    memptr = nn;
                    noMreq(pc, 1);
                    pc = (pc + 1) & 0xFFFF;
                    push(pc);
                    pc = nn;
                } else {
                    skip();
                    skip();
                }
                return;
            case 5:
                if (q == 0) { // PUSH
                    noMreq(ir(), 1);
                    push(getRP2(p, px));
                } else if (p == 0) { // CALL nn
                    int lo = imm();
                    int hi = rdArg(pc);
                    noMreq(pc, 1);
                    pc = (pc + 1) & 0xFFFF;
                    int nn = lo | (hi << 8);
                    memptr = nn;
                    push(pc);
                    pc = nn;
                }
                return;
            case 6:
                alu(y, imm());
                return;
            default: // RST
                noMreq(ir(), 1);
                push(pc);
                pc = y * 8;
                memptr = pc;
                return;
        }
    }

    private void jr() {
        int dd = (byte) rdArg(pc);
        noMreq(pc, 5);
        pc = (pc + dd + 1) & 0xFFFF;
        memptr = pc;
    }

    /** address of (HL) or (IX+d)/(IY+d) for LD r,(..) / ALU (..) — the d form spends 5 extra cycles */
    private int memAddr(int px) {
        if (px == 0) return hl();
        int dd = (byte) rdArg(pc);
        noMreq(pc, 5);
        pc = (pc + 1) & 0xFFFF;
        int addr = ((px == 1 ? ix : iy) + dd) & 0xFFFF;
        memptr = addr;
        return addr;
    }

    /** same for INC/DEC (IX+d) */
    private int memAddr5(int px) {
        return memAddr(px);
    }

    private void daa() {
        int add = 0, carry = f & FC;
        if ((f & FH) != 0 || (a & 0x0F) > 9) add = 6;
        if (carry != 0 || a > 0x99) add |= 0x60;
        if (a > 0x99) carry = FC;
        if ((f & FN) != 0) sub(add);
        else add(add);
        f = (f & ~(FC | FP)) | carry | PAR[a];
    }

    // ------------------------------------------------------------------ CB

    private void execCB() {
        int op = fetchOp();
        int x = op >> 6, y = (op >> 3) & 7, z = op & 7;
        if (z == 6) {
            int addr = hl();
            int v = rd(addr);
            noMreq(addr, 1);
            if (x == 1) {
                bit(y, v, memptr >> 8);
                return;
            }
            int nv = x == 0 ? rot(y, v) : x == 2 ? v & ~(1 << y) : v | (1 << y);
            wr(addr, nv);
            return;
        }
        int v = getR8(z);
        if (x == 0) setR8(z, rot(y, v));
        else if (x == 1) bit(y, v, v);
        else if (x == 2) setR8(z, v & ~(1 << y));
        else setR8(z, v | (1 << y));
    }

    private void execXYCB(int px) {
        int dd = (byte) rdArg(pc);
        pc = (pc + 1) & 0xFFFF;
        int op = rdArg(pc);
        noMreq(pc, 2);
        pc = (pc + 1) & 0xFFFF;
        int addr = ((px == 1 ? ix : iy) + dd) & 0xFFFF;
        memptr = addr;
        int x = op >> 6, y = (op >> 3) & 7, z = op & 7;
        int v = rd(addr);
        noMreq(addr, 1);
        if (x == 1) {
            bit(y, v, addr >> 8);
            return;
        }
        int nv = x == 0 ? rot(y, v) : x == 2 ? v & ~(1 << y) : v | (1 << y);
        wr(addr, nv);
        if (z != 6) setR8(z, nv); // undocumented: result also copied to a register
    }

    // ------------------------------------------------------------------ ED

    private void execED(int op) {
        int x = op >> 6, y = (op >> 3) & 7, z = op & 7, p = y >> 1, q = y & 1;
        if (x == 1) {
            switch (z) {
                case 0: { // IN r,(C)
                    memptr = (bc() + 1) & 0xFFFF;
                    int v = portIn(bc());
                    f = (f & FC) | SZ53P[v];
                    if (y != 6) setR8(y, v);
                    return;
                }
                case 1: // OUT (C),r
                    portOut(bc(), y == 6 ? 0 : getR8(y));
                    memptr = (bc() + 1) & 0xFFFF;
                    return;
                case 2:
                    noMreq(ir(), 7);
                    if (q == 0) sbc16(getRP(p, 0));
                    else adc16(getRP(p, 0));
                    return;
                case 3: {
                    int nn = imm16();
                    if (q == 0) {
                        int v = getRP(p, 0);
                        wr(nn, v & 0xFF);
                        memptr = (nn + 1) & 0xFFFF;
                        wr(memptr, v >> 8);
                    } else {
                        int lo = rd(nn);
                        memptr = (nn + 1) & 0xFFFF;
                        int hi = rd(memptr);
                        setRP(p, lo | (hi << 8), 0);
                    }
                    return;
                }
                case 4: { // NEG
                    int v = a;
                    a = 0;
                    sub(v);
                    return;
                }
                case 5: // RETN / RETI
                    iff1 = iff2;
                    pc = pop();
                    memptr = pc;
                    return;
                case 6:
                    im = (y & 3) == 0 || (y & 3) == 1 ? 0 : (y & 3) == 2 ? 1 : 2;
                    return;
                default:
                    switch (y) {
                        case 0: // LD I,A
                            noMreq(ir(), 1);
                            i = a;
                            return;
                        case 1: // LD R,A
                            noMreq(ir(), 1);
                            setR(a);
                            return;
                        case 2: // LD A,I
                            noMreq(ir(), 1);
                            a = i;
                            f = (f & FC) | SZ53[a] | (iff2 ? FV : 0);
                            return;
                        case 3: // LD A,R
                            noMreq(ir(), 1);
                            a = getR();
                            f = (f & FC) | SZ53[a] | (iff2 ? FV : 0);
                            return;
                        case 4: { // RRD
                            int v = rd(hl());
                            noMreq(hl(), 4);
                            wr(hl(), ((a << 4) | (v >> 4)) & 0xFF);
                            a = (a & 0xF0) | (v & 0x0F);
                            f = (f & FC) | SZ53P[a];
                            memptr = (hl() + 1) & 0xFFFF;
                            return;
                        }
                        case 5: { // RLD
                            int v = rd(hl());
                            noMreq(hl(), 4);
                            wr(hl(), ((v << 4) | (a & 0x0F)) & 0xFF);
                            a = (a & 0xF0) | (v >> 4);
                            f = (f & FC) | SZ53P[a];
                            memptr = (hl() + 1) & 0xFFFF;
                            return;
                        }
                        default: // NOP
                            return;
                    }
            }
        }
        if (x == 2 && z <= 3 && y >= 4) {
            boolean inc = (y & 1) == 0;
            boolean rep = y >= 6;
            switch (z) {
                case 0: ldi(inc, rep); return;
                case 1: cpi(inc, rep); return;
                case 2: ini(inc, rep); return;
                default: outi(inc, rep); return;
            }
        }
        // everything else: 8-T NOP
    }

    private void ldi(boolean inc, boolean rep) {
        int v = rd(hl());
        setBC(bc() - 1);
        wr(de(), v);
        noMreq(de(), 2);
        int dlt = inc ? 1 : -1;
        setDE(de() + dlt);
        setHL(hl() + dlt);
        int n = (v + a) & 0xFF;
        f = (f & (FC | FZ | FS)) | (bc() != 0 ? FV : 0) | (n & F3) | ((n & 0x02) != 0 ? F5 : 0);
        if (rep && bc() != 0) {
            noMreq((de() - dlt) & 0xFFFF, 5);
            pc = (pc - 2) & 0xFFFF;
            memptr = (pc + 1) & 0xFFFF;
        }
    }

    private void cpi(boolean inc, boolean rep) {
        int v = rd(hl());
        int res = (a - v) & 0xFF;
        int lk = ((a & 0x08) >> 3) | ((v & 0x08) >> 2) | ((res & 0x08) >> 1);
        noMreq(hl(), 5);
        int dlt = inc ? 1 : -1;
        setHL(hl() + dlt);
        setBC(bc() - 1);
        f = (f & FC) | (bc() != 0 ? FV | FN : FN) | HC_SUB[lk] | (res != 0 ? 0 : FZ) | (res & FS);
        if ((f & FH) != 0) res = (res - 1) & 0xFF;
        f |= (res & F3) | ((res & 0x02) != 0 ? F5 : 0);
        memptr = (memptr + dlt) & 0xFFFF;
        if (rep && (f & (FV | FZ)) == FV) {
            noMreq((hl() - dlt) & 0xFFFF, 5);
            pc = (pc - 2) & 0xFFFF;
            memptr = (pc + 1) & 0xFFFF;
        }
    }

    private void ini(boolean inc, boolean rep) {
        noMreq(ir(), 1);
        int v = portIn(bc());
        wr(hl(), v);
        int dlt = inc ? 1 : -1;
        memptr = (bc() + dlt) & 0xFFFF;
        b = (b - 1) & 0xFF;
        setHL(hl() + dlt);
        int k = (v + ((c + dlt) & 0xFF));
        f = ((v & 0x80) != 0 ? FN : 0) | (k > 0xFF ? FH | FC : 0) | PAR[(k & 0x07) ^ b] | SZ53[b];
        if (rep && b != 0) {
            noMreq((hl() - dlt) & 0xFFFF, 5);
            pc = (pc - 2) & 0xFFFF;
        }
    }

    private void outi(boolean inc, boolean rep) {
        noMreq(ir(), 1);
        int v = rd(hl());
        b = (b - 1) & 0xFF;
        int dlt = inc ? 1 : -1;
        memptr = (bc() + dlt) & 0xFFFF;
        portOut(bc(), v);
        setHL(hl() + dlt);
        int k = v + l;
        f = ((v & 0x80) != 0 ? FN : 0) | (k > 0xFF ? FH | FC : 0) | PAR[(k & 0x07) ^ b] | SZ53[b];
        if (rep && b != 0) {
            noMreq(bc(), 5);
            pc = (pc - 2) & 0xFFFF;
        }
    }
}
