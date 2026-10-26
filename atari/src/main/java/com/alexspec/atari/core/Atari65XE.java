package com.alexspec.atari.core;

import java.util.Arrays;

/**
 * Initial Atari 65XE memory bus. This is a development scaffold, not yet a
 * cycle-accurate machine: ANTIC/GTIA/POKEY/PIA registers are placeholders.
 *
 * PORTB bit 0 selects OS ROM (1 = visible), bit 1 selects BASIC ROM
 * (0 = visible). The D000-D7FF I/O aperture remains visible.
 */
public final class Atari65XE implements CpuBus {
    public static final int RAM_SIZE = 65536;
    public static final int CPU_HZ_PAL = 1773447;
    private final byte[] ram = new byte[RAM_SIZE];
    private final byte[] os = new byte[16384];
    private final byte[] basic = new byte[8192];
    private final byte[] io = new byte[2048];
    private boolean osLoaded, basicLoaded;
    private int portbLatch, ddrB, controlB;
    private int portaLatch, ddrA, controlA;
    public final M6502 cpu = new M6502(this);

    public Atari65XE() { resetRam(); }

    public void resetRam() {
        Arrays.fill(ram, (byte) 0);
        Arrays.fill(io, (byte) 0);
        reset();
    }

    /** Development cold CPU/PIA reset preserving RAM; real RESET-key behaviour comes with chips. */
    public void reset() {
        portbLatch=ddrB=controlB=portaLatch=ddrA=controlA=0;
        cpu.reset();
    }

    public void loadOs(byte[] image) {
        if (image == null || image.length != os.length)
            throw new IllegalArgumentException("OS ROM must be exactly 16384 bytes");
        System.arraycopy(image, 0, os, 0, os.length);
        osLoaded = true;
    }

    public void loadBasic(byte[] image) {
        if (image == null || image.length != basic.length)
            throw new IllegalArgumentException("BASIC ROM must be exactly 8192 bytes");
        System.arraycopy(image, 0, basic, 0, basic.length);
        basicLoaded = true;
    }

    public boolean hasOs() { return osLoaded; }
    public boolean hasBasic() { return basicLoaded; }
    /** Unconnected PIA input pins pull high; only output-configured bits drive banking. */
    public int portb() { return (portbLatch & ddrB) | (~ddrB & 255); }

    public void resetCpu() { cpu.reset(); }
    public int stepInstruction() {
        if (!osLoaded) throw new IllegalStateException("Load an OS ROM first");
        return cpu.step();
    }

    private boolean osVisible() { return osLoaded && (portb()&1)!=0; }
    private boolean basicVisible() { return basicLoaded && (portb()&2)==0; }
    private boolean selfTest(int a) { return osVisible() && (portb()&128)==0 && a>=0x5000 && a<0x5800; }
    private boolean osAddress(int a) { return a>=0xc000 && (a<0xd000 || a>=0xd800); }

    private int readPia(int reg) {
        switch (reg) {
            case 0: return (controlA&4)==0 ? ddrA : (portaLatch&ddrA)|(~ddrA&255);
            case 1: return (controlB&4)==0 ? ddrB : portb();
            case 2: return controlA;
            default: return controlB;
        }
    }

    private void writePia(int reg,int value) {
        switch (reg) {
            case 0: if ((controlA&4)==0) ddrA=value; else portaLatch=value; break;
            case 1: if ((controlB&4)==0) ddrB=value; else portbLatch=value; break;
            case 2: controlA=value&63; break;
            default: controlB=value&63; break;
        }
    }

    public int read(int address) {
        int a = address & 0xFFFF;
        if (a >= 0xD000 && a <= 0xD7FF) {
            if ((a&0xff00)==0xd300) return readPia(a&3);
            return io[a - 0xD000] & 0xFF;
        }
        if (selfTest(a)) return os[0x1000+a-0x5000]&255;
        if (a >= 0xA000 && a <= 0xBFFF && basicVisible())
            return basic[a - 0xA000] & 0xFF;
        if (osVisible() && osAddress(a))
            return os[a - 0xC000] & 0xFF;
        return ram[a] & 0xFF;
    }

    public void write(int address, int value) {
        int a = address & 0xFFFF, v = value & 0xFF;
        if (a >= 0xD000 && a <= 0xD7FF) {
            if ((a&0xff00)==0xd300) writePia(a&3,v);
            else io[a - 0xD000] = (byte) v;
            return;
        }
        // XL/XE ROM overlays inhibit RAM writes; bank ROM out to access underlying RAM.
        if (selfTest(a) || (basicVisible() && a>=0xa000 && a<0xc000)
                || (osVisible() && osAddress(a))) return;
        ram[a] = (byte) v;
    }

    public int peekRam(int address) { return ram[address & 0xFFFF] & 0xFF; }

    public String region(int address) {
        int a = address & 0xFFFF;
        if (a < 0x0100) return "Zero page";
        if (a < 0x0200) return "CPU stack";
        if (a >= 0xD000 && a < 0xD100) return "GTIA / cartridge I/O";
        if (a >= 0xD200 && a < 0xD300) return "POKEY";
        if (a >= 0xD300 && a < 0xD400) return "PIA / PORTB";
        if (a >= 0xD400 && a < 0xD500) return "ANTIC";
        if (a >= 0xD000 && a < 0xD800) return "I/O";
        if (selfTest(a)) return "OS self-test ROM";
        if (a >= 0xA000 && a < 0xC000 && basicVisible()) return "BASIC ROM";
        if (osVisible() && osAddress(a)) return "OS ROM";
        return "RAM";
    }
}
