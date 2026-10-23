package com.alexspec.specialist.core;

/**
 * Records, per byte of the 64 KB address space, when it was last read, written and executed
 * (opcode or operand fetch), and renders a "live" memory map.
 *
 * Map layout follows the Specialist video memory: a byte is a horizontal strip of 8 pixels,
 * consecutive addresses go DOWN a column of 256 bytes, so column = high byte, row = low byte.
 * 256 columns are split into two bands: 0000-7FFF on top, 8000-FFFF below (1024 x 512 pixels).
 */
public final class MemTrace {
    public static final int MAP_W = 1024;
    public static final int MAP_H = 512;

    public static final int KIND_NONE = 0, KIND_READ = 1, KIND_WRITE = 2, KIND_RW = 3, KIND_EXEC = 4;

    /** Stamps are "trace frames"; the frame counter advances once per real-time display frame. */
    public final int[] lastRead = new int[65536];
    public final int[] lastWrite = new int[65536];
    public final int[] lastExec = new int[65536];

    public volatile boolean enabled;
    /** current trace frame; starts high so that zero-initialised stamps count as "long ago" */
    public int now = 1 << 20;
    /** how many frames a flash lasts (5 frames = 0.1 s at 50 Hz) */
    public int flashFrames = 5;

    // colours: bright = for 1 bits, dark = for 0 bits
    private int[] bright = new int[5];
    private int[] dark = new int[5];
    private int baseOn = 0xFFC8C8C8, baseOff = 0xFF0E0E0E;
    /** palette[kind][level]: level 0 = freshest ... flashFrames-1, then base */
    private int[][] palOn, palOff;

    public MemTrace() {
        setColors(0xFF30FF40, 0xFFFF3030, 0xFFFFE030, 0xFF20E8FF);
    }

    public synchronized void setColors(int read, int write, int rw, int exec) {
        int[] c = {0, read, write, rw, exec};
        for (int k = 1; k < 5; k++) {
            bright[k] = c[k];
            dark[k] = scale(c[k], 0.30f);
        }
        rebuild();
    }

    public synchronized void setFlashFrames(int n) {
        flashFrames = Math.max(1, Math.min(50, n));
        rebuild();
    }

    private void rebuild() {
        int n = flashFrames;
        palOn = new int[5][n];
        palOff = new int[5][n];
        for (int k = 1; k < 5; k++) {
            for (int lvl = 0; lvl < n; lvl++) {
                // fade from full colour (level 0) towards the base colour
                float t = n == 1 ? 1f : 1f - (float) lvl / n;
                palOn[k][lvl] = mix(baseOn, bright[k], t);
                palOff[k][lvl] = mix(baseOff, dark[k], t);
            }
        }
    }

    private static int scale(int c, float f) {
        int r = (int) (((c >> 16) & 0xFF) * f), g = (int) (((c >> 8) & 0xFF) * f), b = (int) ((c & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Advance one display frame (call once per real 20 ms frame, not while paused). */
    public void tick() {
        now++;
    }

    public void clear() {
        java.util.Arrays.fill(lastRead, 0);
        java.util.Arrays.fill(lastWrite, 0);
        java.util.Arrays.fill(lastExec, 0);
    }

    /** Kind of the freshest access still inside the flash window, or KIND_NONE. */
    public int kindAt(int addr, int[] levelOut) {
        int n = flashFrames;
        int ax = now - lastExec[addr], aw = now - lastWrite[addr], ar = now - lastRead[addr];
        if (ax < n) {
            levelOut[0] = ax;
            return KIND_EXEC;
        }
        boolean w = aw < n, r = ar < n;
        if (w && r) {
            levelOut[0] = Math.min(aw, ar);
            return KIND_RW;
        }
        if (w) {
            levelOut[0] = aw;
            return KIND_WRITE;
        }
        if (r) {
            levelOut[0] = ar;
            return KIND_READ;
        }
        return KIND_NONE;
    }

    /** Map pixel position of an address. */
    public static int mapX(int addr) {
        return ((addr >> 8) & 0x7F) * 8;
    }

    public static int mapY(int addr) {
        return (addr & 0xFF) + ((addr >> 15) & 1) * 256;
    }

    /** Address under a map pixel. */
    public static int addrAt(int x, int y) {
        if (x < 0 || y < 0 || x >= MAP_W || y >= MAP_H) return -1;
        int col = x / 8 + (y >= 256 ? 128 : 0);
        return (col << 8) | (y & 0xFF);
    }

    /** Renders the map; {@code mem} returns raw byte values without side effects. */
    public synchronized void render(Specialist mem, int[] out) {
        int n = flashFrames;
        int[] lvl = new int[1];
        for (int addr = 0; addr < 65536; addr++) {
            int v = mem.rawRead(addr);
            int on = baseOn, off = baseOff;
            int kind = kindAt(addr, lvl);
            if (kind != KIND_NONE) {
                int l = Math.min(lvl[0], n - 1);
                on = palOn[kind][l];
                off = palOff[kind][l];
            }
            int p = mapY(addr) * MAP_W + mapX(addr);
            for (int b = 0; b < 8; b++, v <<= 1) out[p + b] = (v & 0x80) != 0 ? on : off;
        }
    }
}
