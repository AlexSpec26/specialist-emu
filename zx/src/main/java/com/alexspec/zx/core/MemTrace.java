package com.alexspec.zx.core;

/**
 * Records, per byte of the 64 KB address space, when it was last read, written and executed
 * (opcode or operand fetch), and renders a "live" memory map like the Specialist one:
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
