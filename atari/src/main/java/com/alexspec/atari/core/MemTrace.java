package com.alexspec.atari.core;

/**
 * Records, per byte of the 64 KB address space, when it was last read, written and executed
 * (opcode or operand fetch), and renders a "live" memory map like the Specialist and ZX ones:
 * 1 bit = 1 pixel, consecutive lines go DOWN a column of 256 lines; each line holds
 * {@link #layout} bytes (1..4) side by side, bit 7 on the left. A column therefore holds
 * 256 x W bytes and is 8 x W pixels wide; the map is 2048 x 256 pixels (2064 for 3 bytes).
 * Choosing the width of the game's graphics (1 byte for 8x8 tiles and fonts, 2 for 16-pixel
 * sprites...) makes them appear as pictures.
 */
public final class MemTrace {
    /** largest width (3 bytes per line: 86 columns of 24 pixels), for buffer sizing */
    public static final int MAP_W = 2064;
    public static final int MAP_H = 256;

    /** bytes per map line, 1..4; read by the renderer and the UI */
    public volatile int layout = 1;

    public static int width(int layout) {
        int w = w(layout), cols = (65536 + 256 * w - 1) / (256 * w);
        return cols * 8 * w;
    }

    public static int height(int layout) {
        return MAP_H;
    }

    /** map pixels per byte horizontally */
    public static int cellW(int layout) {
        return 8;
    }

    private static int w(int layout) {
        return layout < 1 ? 1 : Math.min(4, layout);
    }

    /** top-left map pixel of a byte */
    public static int posX(int layout, int addr) {
        int w = w(layout), colBytes = 256 * w;
        return (addr / colBytes) * w * 8 + (addr % w) * 8;
    }

    public static int posY(int layout, int addr) {
        int w = w(layout);
        return (addr % (256 * w)) / w;
    }

    /** address under a map pixel, -1 outside */
    public static int addrAt(int layout, int x, int y) {
        if (x < 0 || y < 0 || x >= width(layout) || y >= MAP_H) return -1;
        int w = w(layout);
        int col = x / (8 * w), k = (x % (8 * w)) / 8;
        int a = col * 256 * w + y * w + k;
        return a < 65536 ? a : -1;
    }

    public static final int KIND_NONE = 0, KIND_READ = 1, KIND_WRITE = 2, KIND_RW = 3, KIND_EXEC = 4;

    private static final long NEVER = Long.MIN_VALUE / 4;

    /**
     * Stamps are emulated time in machine cycles (the start of the accessing instruction + 1), so a flash follows the
     * emulation: slowed down 100 times, it lasts 100 times fewer cycles.
     */
    public final long[] lastRead = new long[65536];
    public final long[] lastWrite = new long[65536];
    public final long[] lastExec = new long[65536];

    public volatile boolean enabled;
    /** emulated time the picture is drawn for (set by the renderer) */
    public volatile long now;
    /** how long a flash lasts, in cycles of emulated time (set from the speed) */
    public volatile long window = 350_000;
    /**
     * single-step mode: if >= 0, only accesses made since this moment (the last executed
     * instruction) are lit, at full brightness
     */
    public volatile long stepFrom = -1;
    /** number of fade levels; also the flash length in 1/50 s of real time */
    public int flashFrames = 5;

    // colours: bright = for 1 bits, dark = for 0 bits
    private int[] bright = new int[5];
    private int[] dark = new int[5];
    private int baseOn = 0xFFC8C8C8, baseOff = 0xFF0E0E0E;
    /** palette[kind][level]: level 0 = freshest ... flashFrames-1, then base */
    private int[][] palOn, palOff;

    public MemTrace() {
        clear();
        setColors(0xFF30FF40, 0xFFFF2A9E, 0xFFFFE030, 0xFF20E8FF);
    }

    public synchronized void setColors(int read, int write, int rw, int exec) {
        int[] c = {0, read, write, rw, exec};
        for (int k = 1; k < 5; k++) {
            bright[k] = c[k];
            dark[k] = scale(c[k], 0.55f); // 0 bits glow too, so a flash is visible on dark data
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

    public void clear() {
        java.util.Arrays.fill(lastRead, NEVER);
        java.util.Arrays.fill(lastWrite, NEVER);
        java.util.Arrays.fill(lastExec, NEVER);
    }

    /** age of a stamp in fade levels, or -1 if not lit */
    private int level(long stamp) {
        long age = now - stamp;
        if (age < 0) return -1; // happened "later" (we went back in time)
        long from = stepFrom;
        if (from >= 0) return stamp > from ? 0 : -1; // every access of an instruction is after its start
        long w = Math.max(1, window);
        if (age >= w) return -1;
        return (int) (age * flashFrames / w);
    }

    /** Kind of the freshest access still inside the flash window, or KIND_NONE. */
    public int kindAt(int addr, int[] levelOut) {
        int lx = level(lastExec[addr]);
        if (lx >= 0) {
            levelOut[0] = lx;
            return KIND_EXEC;
        }
        int lw = level(lastWrite[addr]), lr = level(lastRead[addr]);
        if (lw >= 0 && lr >= 0) {
            levelOut[0] = Math.min(lw, lr);
            return KIND_RW;
        }
        if (lw >= 0) {
            levelOut[0] = lw;
            return KIND_WRITE;
        }
        if (lr >= 0) {
            levelOut[0] = lr;
            return KIND_READ;
        }
        return KIND_NONE;
    }


    /** Current on/off colours of one byte (index 0 = colour of 1 bits, 1 = colour of 0 bits). */
    public synchronized void colors(int addr, int[] out) {
        int[] lvl = new int[1];
        int kind = kindAt(addr, lvl);
        if (kind == KIND_NONE) {
            out[0] = baseOn;
            out[1] = baseOff;
        } else {
            int l = Math.min(lvl[0], flashFrames - 1);
            out[0] = palOn[kind][l];
            out[1] = palOff[kind][l];
        }
    }

    /**
     * Renders the map in the given layout into {@code out} (width(layout) x height(layout)).
     * {@code mem} supplies raw byte values without side effects.
     */
    public interface Mem {
        int rawRead(int addr);
    }

    public synchronized void render(Mem mem, int[] out, int layout) {
        int n = flashFrames;
        int[] lvl = new int[1];
        int w = w(layout), colBytes = 256 * w, stride = width(layout);
        java.util.Arrays.fill(out, 0, stride * MAP_H, baseOff);
        for (int addr = 0; addr < 65536; addr++) {
            int v = mem.rawRead(addr);
            int on = baseOn, off = baseOff;
            int kind = kindAt(addr, lvl);
            if (kind != KIND_NONE) {
                int l = Math.min(lvl[0], n - 1);
                on = palOn[kind][l];
                off = palOff[kind][l];
            }
            int o = addr % colBytes;
            int p = (o / w) * stride + (addr / colBytes) * w * 8 + (o % w) * 8;
            for (int b = 0; b < 8; b++, v <<= 1) out[p + b] = (v & 0x80) != 0 ? on : off;
        }
    }
}
