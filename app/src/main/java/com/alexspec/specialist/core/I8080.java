package com.alexspec.specialist.core;

/**
 * Intel 8080 (КР580ВМ80А) interpreter with documented cycle timings.
 * Passes 8080PRE, TST8080, CPUTEST and 8080EXM.
 */
public final class I8080 {
    private static final int[] CYCLES = {
        4, 10, 7, 5, 5, 5, 7, 4, 4, 10, 7, 5, 5, 5, 7, 4,
        4, 10, 7, 5, 5, 5, 7, 4, 4, 10, 7, 5, 5, 5, 7, 4,
        4, 10, 16, 5, 5, 5, 7, 4, 4, 10, 16, 5, 5, 5, 7, 4,
        4, 10, 13, 5, 10, 10, 10, 4, 4, 10, 13, 5, 5, 5, 7, 4,
        5, 5, 5, 5, 5, 5, 7, 5, 5, 5, 5, 5, 5, 5, 7, 5,
        5, 5, 5, 5, 5, 5, 7, 5, 5, 5, 5, 5, 5, 5, 7, 5,
        5, 5, 5, 5, 5, 5, 7, 5, 5, 5, 5, 5, 5, 5, 7, 5,
        7, 7, 7, 7, 7, 7, 7, 7, 5, 5, 5, 5, 5, 5, 7, 5,
        4, 4, 4, 4, 4, 4, 7, 4, 4, 4, 4, 4, 4, 4, 7, 4,
        4, 4, 4, 4, 4, 4, 7, 4, 4, 4, 4, 4, 4, 4, 7, 4,
        4, 4, 4, 4, 4, 4, 7, 4, 4, 4, 4, 4, 4, 4, 7, 4,
        4, 4, 4, 4, 4, 4, 7, 4, 4, 4, 4, 4, 4, 4, 7, 4,
        5, 10, 10, 10, 11, 11, 7, 11, 5, 10, 10, 10, 11, 17, 7, 11,
        5, 10, 10, 10, 11, 11, 7, 11, 5, 10, 10, 10, 11, 17, 7, 11,
        5, 10, 10, 18, 11, 11, 7, 11, 5, 5, 10, 4, 11, 17, 7, 11,
        5, 10, 10, 4, 11, 11, 7, 11, 5, 5, 10, 4, 11, 17, 7, 11,
    };

    private static final boolean[] PARITY = new boolean[256];

    static {
        for (int i = 0; i < 256; i++) PARITY[i] = (Integer.bitCount(i) & 1) == 0;
    }

    private final Bus bus;

    public int a, b, c, d, e, h, l, sp, pc;
    public boolean fs, fz, fac, fp, fcy;
    public boolean halted;
    public boolean inte;
    private int pendingEi;

    public I8080(Bus bus) {
        this.bus = bus;
    }

    public void reset(int startPc) {
        pc = startPc & 0xFFFF;
        halted = false;
        inte = false;
        pendingEi = 0;
    }

    // ---- flags ----
    public int getF() {
        return (fs ? 0x80 : 0) | (fz ? 0x40 : 0) | (fac ? 0x10 : 0) | (fp ? 0x04 : 0) | 0x02 | (fcy ? 0x01 : 0);
    }

    public void setF(int f) {
        fs = (f & 0x80) != 0;
        fz = (f & 0x40) != 0;
        fac = (f & 0x10) != 0;
        fp = (f & 0x04) != 0;
        fcy = (f & 0x01) != 0;
    }

    private void szp(int v) {
        fs = (v & 0x80) != 0;
        fz = v == 0;
        fp = PARITY[v];
    }

    // ---- memory helpers ----
    private int fetch() {
        int v = bus.fetch(pc);
        pc = (pc + 1) & 0xFFFF;
        return v;
    }

    private int fetch16() {
        int lo = fetch();
        return lo | (fetch() << 8);
    }

    private int read16(int addr) {
        return bus.read(addr) | (bus.read((addr + 1) & 0xFFFF) << 8);
    }

    private void write16(int addr, int v) {
        bus.write(addr, v & 0xFF);
        bus.write((addr + 1) & 0xFFFF, (v >> 8) & 0xFF);
    }

    private void push(int v) {
        sp = (sp - 2) & 0xFFFF;
        write16(sp, v);
    }

    private int pop() {
        int v = read16(sp);
        sp = (sp + 2) & 0xFFFF;
        return v;
    }

    public int bc() { return (b << 8) | c; }
    public int de() { return (d << 8) | e; }
    public int hl() { return (h << 8) | l; }

    private void setBC(int v) { b = (v >> 8) & 0xFF; c = v & 0xFF; }
    private void setDE(int v) { d = (v >> 8) & 0xFF; e = v & 0xFF; }
    private void setHL(int v) { h = (v >> 8) & 0xFF; l = v & 0xFF; }

    private int getReg(int r) {
        switch (r) {
            case 0: return b;
            case 1: return c;
            case 2: return d;
            case 3: return e;
            case 4: return h;
            case 5: return l;
            case 6: return bus.read(hl());
            default: return a;
        }
    }

    private void setReg(int r, int v) {
        switch (r) {
            case 0: b = v; break;
            case 1: c = v; break;
            case 2: d = v; break;
            case 3: e = v; break;
            case 4: h = v; break;
            case 5: l = v; break;
            case 6: bus.write(hl(), v); break;
            default: a = v; break;
        }
    }

    // ---- ALU ----
    private void add(int v, int carry) {
        int res = a + v + carry;
        fac = ((a ^ v ^ res) & 0x10) != 0;
        fcy = (res & 0x100) != 0;
        a = res & 0xFF;
        szp(a);
    }

    private int subRes(int v, int borrow) {
        int nv = (~v) & 0xFF;
        int res = a + nv + (borrow ^ 1);
        fac = ((a ^ nv ^ res) & 0x10) != 0;
        fcy = (res & 0x100) == 0;
        res &= 0xFF;
        szp(res);
        return res;
    }

    private void ana(int v) {
        fac = ((a | v) & 0x08) != 0;
        a &= v;
        fcy = false;
        szp(a);
    }

    private void xra(int v) {
        a ^= v;
        fac = false;
        fcy = false;
        szp(a);
    }

    private void ora(int v) {
        a |= v;
        fac = false;
        fcy = false;
        szp(a);
    }

    private void alu(int op, int v) {
        switch (op) {
            case 0: add(v, 0); break;
            case 1: add(v, fcy ? 1 : 0); break;
            case 2: a = subRes(v, 0); break;
            case 3: a = subRes(v, fcy ? 1 : 0); break;
            case 4: ana(v); break;
            case 5: xra(v); break;
            case 6: ora(v); break;
            default: subRes(v, 0); break;
        }
    }

    private int inr(int v) {
        v = (v + 1) & 0xFF;
        fac = (v & 0x0F) == 0;
        szp(v);
        return v;
    }

    private int dcr(int v) {
        v = (v - 1) & 0xFF;
        fac = (v & 0x0F) != 0x0F;
        szp(v);
        return v;
    }

    private void daa() {
        int corr = 0;
        boolean cy = fcy;
        int lsb = a & 0x0F;
        int msb = a >> 4;
        if (fac || lsb > 9) corr += 0x06;
        if (fcy || msb > 9 || (msb >= 9 && lsb > 9)) {
            corr += 0x60;
            cy = true;
        }
        add(corr, 0);
        fcy = cy;
    }

    private void dad(int v) {
        int res = hl() + v;
        fcy = (res & 0x10000) != 0;
        setHL(res & 0xFFFF);
    }

    private boolean cond(int cc) {
        switch (cc) {
            case 0: return !fz;
            case 1: return fz;
            case 2: return !fcy;
            case 3: return fcy;
            case 4: return !fp;
            case 5: return fp;
            case 6: return !fs;
            default: return fs;
        }
    }

    /** Raise an interrupt with RST n (0..7). Returns cycles used (0 if not accepted). */
    public int interrupt(int rst) {
        if (!inte) return 0;
        inte = false;
        halted = false;
        push(pc);
        pc = rst * 8;
        return 11;
    }

    /** Execute one instruction; returns the number of clock cycles it took. */
    public int step() {
        if (pendingEi > 0 && --pendingEi == 0) inte = true;
        if (halted) return 4;
        int op = fetch();
        int cyc = CYCLES[op];

        if (op >= 0x40 && op < 0x80) {
            if (op == 0x76) {
                halted = true;
                return cyc;
            }
            setReg((op >> 3) & 7, getReg(op & 7));
            return cyc;
        }
        if (op >= 0x80 && op < 0xC0) {
            alu((op >> 3) & 7, getReg(op & 7));
            return cyc;
        }

        switch (op) {
            case 0x00: case 0x08: case 0x10: case 0x18:
            case 0x20: case 0x28: case 0x30: case 0x38:
                break;
            case 0x01: setBC(fetch16()); break;
            case 0x11: setDE(fetch16()); break;
            case 0x21: setHL(fetch16()); break;
            case 0x31: sp = fetch16(); break;
            case 0x02: bus.write(bc(), a); break;
            case 0x12: bus.write(de(), a); break;
            case 0x0A: a = bus.read(bc()); break;
            case 0x1A: a = bus.read(de()); break;
            case 0x22: write16(fetch16(), hl()); break;
            case 0x2A: setHL(read16(fetch16())); break;
            case 0x32: bus.write(fetch16(), a); break;
            case 0x3A: a = bus.read(fetch16()); break;
            case 0x03: setBC((bc() + 1) & 0xFFFF); break;
            case 0x13: setDE((de() + 1) & 0xFFFF); break;
            case 0x23: setHL((hl() + 1) & 0xFFFF); break;
            case 0x33: sp = (sp + 1) & 0xFFFF; break;
            case 0x0B: setBC((bc() - 1) & 0xFFFF); break;
            case 0x1B: setDE((de() - 1) & 0xFFFF); break;
            case 0x2B: setHL((hl() - 1) & 0xFFFF); break;
            case 0x3B: sp = (sp - 1) & 0xFFFF; break;
            case 0x09: dad(bc()); break;
            case 0x19: dad(de()); break;
            case 0x29: dad(hl()); break;
            case 0x39: dad(sp); break;
            case 0x04: case 0x0C: case 0x14: case 0x1C:
            case 0x24: case 0x2C: case 0x34: case 0x3C: {
                int r = (op >> 3) & 7;
                setReg(r, inr(getReg(r)));
                break;
            }
            case 0x05: case 0x0D: case 0x15: case 0x1D:
            case 0x25: case 0x2D: case 0x35: case 0x3D: {
                int r = (op >> 3) & 7;
                setReg(r, dcr(getReg(r)));
                break;
            }
            case 0x06: case 0x0E: case 0x16: case 0x1E:
            case 0x26: case 0x2E: case 0x36: case 0x3E:
                setReg((op >> 3) & 7, fetch());
                break;
            case 0x07: fcy = (a & 0x80) != 0; a = ((a << 1) | (fcy ? 1 : 0)) & 0xFF; break;
            case 0x0F: fcy = (a & 1) != 0; a = (a >> 1) | (fcy ? 0x80 : 0); break;
            case 0x17: { boolean cy = fcy; fcy = (a & 0x80) != 0; a = ((a << 1) | (cy ? 1 : 0)) & 0xFF; break; }
            case 0x1F: { boolean cy = fcy; fcy = (a & 1) != 0; a = (a >> 1) | (cy ? 0x80 : 0); break; }
            case 0x27: daa(); break;
            case 0x2F: a = (~a) & 0xFF; break;
            case 0x37: fcy = true; break;
            case 0x3F: fcy = !fcy; break;

            // jumps
            case 0xC3: case 0xCB: pc = fetch16(); break;
            case 0xC2: case 0xCA: case 0xD2: case 0xDA:
            case 0xE2: case 0xEA: case 0xF2: case 0xFA: {
                int addr = fetch16();
                if (cond((op >> 3) & 7)) pc = addr;
                break;
            }
            case 0xCD: case 0xDD: case 0xED: case 0xFD: {
                int addr = fetch16();
                push(pc);
                pc = addr;
                break;
            }
            case 0xC4: case 0xCC: case 0xD4: case 0xDC:
            case 0xE4: case 0xEC: case 0xF4: case 0xFC: {
                int addr = fetch16();
                if (cond((op >> 3) & 7)) {
                    push(pc);
                    pc = addr;
                    cyc += 6;
                }
                break;
            }
            case 0xC9: case 0xD9: pc = pop(); break;
            case 0xC0: case 0xC8: case 0xD0: case 0xD8:
            case 0xE0: case 0xE8: case 0xF0: case 0xF8:
                if (cond((op >> 3) & 7)) {
                    pc = pop();
                    cyc += 6;
                }
                break;
            case 0xC7: case 0xCF: case 0xD7: case 0xDF:
            case 0xE7: case 0xEF: case 0xF7: case 0xFF:
                push(pc);
                pc = op & 0x38;
                break;
            case 0xE9: pc = hl(); break;

            // stack
            case 0xC5: push(bc()); break;
            case 0xD5: push(de()); break;
            case 0xE5: push(hl()); break;
            case 0xF5: push((a << 8) | getF()); break;
            case 0xC1: setBC(pop()); break;
            case 0xD1: setDE(pop()); break;
            case 0xE1: setHL(pop()); break;
            case 0xF1: { int v = pop(); a = v >> 8; setF(v & 0xFF); break; }
            case 0xE3: {
                int v = read16(sp);
                write16(sp, hl());
                setHL(v);
                break;
            }
            case 0xF9: sp = hl(); break;
            case 0xEB: { int t = hl(); setHL(de()); setDE(t); break; }

            // immediate ALU
            case 0xC6: add(fetch(), 0); break;
            case 0xCE: add(fetch(), fcy ? 1 : 0); break;
            case 0xD6: a = subRes(fetch(), 0); break;
            case 0xDE: a = subRes(fetch(), fcy ? 1 : 0); break;
            case 0xE6: ana(fetch()); break;
            case 0xEE: xra(fetch()); break;
            case 0xF6: ora(fetch()); break;
            case 0xFE: subRes(fetch(), 0); break;

            // io / control
            case 0xD3: bus.out(fetch(), a); break;
            case 0xDB: a = bus.in(fetch()) & 0xFF; break;
            case 0xF3: inte = false; pendingEi = 0; break;
            case 0xFB: pendingEi = 2; break;
            default: break;
        }
        return cyc;
    }

    // ---- state ----
    // Kept separately so version-1 machine saves retain their original CPU layout.
    int pendingEi() { return pendingEi; }
    void restorePendingEi(int delay) { pendingEi = delay; }

    public void save(java.io.DataOutputStream out) throws java.io.IOException {
        out.writeShort(pc); out.writeShort(sp);
        out.writeByte(a); out.writeByte(getF());
        out.writeByte(b); out.writeByte(c); out.writeByte(d); out.writeByte(e);
        out.writeByte(h); out.writeByte(l);
        out.writeBoolean(halted); out.writeBoolean(inte);
    }

    public void load(java.io.DataInputStream in) throws java.io.IOException {
        pc = in.readUnsignedShort(); sp = in.readUnsignedShort();
        a = in.readUnsignedByte(); setF(in.readUnsignedByte());
        b = in.readUnsignedByte(); c = in.readUnsignedByte();
        d = in.readUnsignedByte(); e = in.readUnsignedByte();
        h = in.readUnsignedByte(); l = in.readUnsignedByte();
        halted = in.readBoolean(); inte = in.readBoolean();
        pendingEi = 0;
    }
}
