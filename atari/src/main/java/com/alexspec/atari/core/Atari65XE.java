package com.alexspec.atari.core;

import java.util.Arrays;

/**
 * Initial Atari 65XE memory bus. This is a development scaffold, not yet a
 * cycle-accurate machine: ANTIC/GTIA/POKEY/PIA registers are placeholders.
 *
 * PORTB bit 0 selects OS ROM (0 = visible), bit 1 selects BASIC ROM
 * (0 = visible). The D000-D7FF I/O aperture remains visible.
 */
public final class Atari65XE {
    public static final int RAM_SIZE = 65536;
    public static final int CPU_HZ_PAL = 1773447;
    private final byte[] ram = new byte[RAM_SIZE];
    private final byte[] os = new byte[16384];
    private final byte[] basic = new byte[8192];
    private final byte[] io = new byte[2048];
    private boolean osLoaded, basicLoaded;
    private int portb = 0xFF;

    public void resetRam() {
        Arrays.fill(ram, (byte) 0);
        Arrays.fill(io, (byte) 0);
        portb = 0xFF;
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
    public int portb() { return portb; }

    public int read(int address) {
        int a = address & 0xFFFF;
        if (a >= 0xD000 && a <= 0xD7FF) {
            if (a == 0xD301) return portb;
            return io[a - 0xD000] & 0xFF;
        }
        if (a >= 0xA000 && a <= 0xBFFF && (portb & 2) == 0 && basicLoaded)
            return basic[a - 0xA000] & 0xFF;
        if ((portb & 1) == 0 && osLoaded && (a >= 0xC000 && a < 0xD000 || a >= 0xD800))
            return os[a - 0xC000] & 0xFF;
        return ram[a] & 0xFF;
    }

    public void write(int address, int value) {
        int a = address & 0xFFFF, v = value & 0xFF;
        if (a >= 0xD000 && a <= 0xD7FF) {
            if (a == 0xD301) portb = v;
            else io[a - 0xD000] = (byte) v;
            return;
        }
        // Writes reach underlying RAM even when ROM is selected.
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
        if (a >= 0xA000 && a < 0xC000 && (portb & 2) == 0 && basicLoaded) return "BASIC ROM";
        if ((portb & 1) == 0 && osLoaded && (a >= 0xC000 && a < 0xD000 || a >= 0xD800)) return "OS ROM";
        return "RAM";
    }
}