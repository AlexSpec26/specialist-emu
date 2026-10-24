package com.alexspec.specialist.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * The Specialist (ПК "Специалист") machine.
 *
 * Memory map: 0000-8FFF RAM, 9000-BFFF video RAM (48 columns x 256 lines, 1 bit per pixel),
 * C000-EFFF ROM, F000-F7FF unused, F800-FFFF keyboard PPI КР580ВВ55 (mirrored, A0-A1).
 * PPI port C: bits 0-3 keyboard columns 8-11, bit 5 beeper, bit 7 tape out,
 * bits 7,6,4 ink colour on colour Specialist.
 */
public final class Specialist implements Bus {
    public static final int CPU_HZ = 2_000_000;
    public static final int FPS = 50;
    public static final int CYCLES_PER_FRAME = CPU_HZ / FPS;
    public static final int SAMPLE_RATE = 44100;
    public static final int WIDTH = 384;
    public static final int HEIGHT = 256;
    public static final int START_ADDR = 0xC000;
    public static final int ROM_SIZE = 0x3000;

    public static final int COLOR_WHITE = 0;
    public static final int COLOR_GREEN = 1;
    public static final int COLOR_AMBER = 2;
    public static final int COLOR_8 = 3;

    private static final int[] PALETTE8 = {
        0xFFFFFFFF, 0xFFFFFF00, 0xFFFF00FF, 0xFFFF0000,
        0xFF00FFFF, 0xFF00FF00, 0xFF0000FF, 0xFF000000,
    };
    private static final int[] MONO_INK = {0xFFE8E8E8, 0xFF40FF70, 0xFFFFB030};
    private static final int PAPER = 0xFF000000;

    private final byte[] ram = new byte[0xC000];
    private final byte[] colorRam = new byte[0x3000];
    private final byte[] rom = new byte[ROM_SIZE];
    private boolean romLoaded;

    public final I8080 cpu = new I8080(this);

    // PPI state
    private int portA, portB, portC;
    private boolean aIn = true, bIn = true, cLoIn = true, cHiIn = true;
    private int curColor;
    private boolean beeper;
    /** tape output: port C bit 7 */
    private boolean tapeOut;
    /** total emulated CPU cycles (time base for the tape) */
    private long cycleCount;
    /** tape input source; null = silence */
    public volatile TapeInput tapeInput;
    /** invert the tape input polarity */
    public volatile boolean tapeInvert;
    /** listeners for tape output edges (e.g. recording); called on the emulation thread */
    public interface TapeOutListener {
        void onTapeEdge(long cycle, boolean level);
    }
    public volatile TapeOutListener tapeOutListener;
    /** loudness of the tape signals in the speaker, 0 = mute */
    public volatile float tapeOutVolume = 0f, tapeInVolume = 0f;

    // keyboard: per column, bit per row; written from UI thread
    private final AtomicIntegerArray keyCols = new AtomicIntegerArray(SpecKeys.COLS);
    private volatile boolean shiftDown;
    private final int[] pressCount = new int[SpecKeys.COUNT];

    // audio
    private long sampleAcc;
    private int winCyc, winHi, winTapeOut, winTapeIn;
    private float hpPrevIn, hpPrevOut;
    private short[] audioOut;
    private int audioPos;
    public float volume = 0.35f;

    public Specialist() {
        java.util.Arrays.fill(rom, (byte) 0xFF);
        reset();
    }

    // ------------------------------------------------------------------ ROM / reset

    /** Loads a monitor ROM image (up to 12 KB) mapped at C000. */
    public void loadRom(byte[] data) {
        java.util.Arrays.fill(rom, (byte) 0xFF);
        System.arraycopy(data, 0, rom, 0, Math.min(data.length, ROM_SIZE));
        romLoaded = true;
    }

    public boolean isRomLoaded() {
        return romLoaded;
    }

    public void reset() {
        cpu.reset(START_ADDR);
        aIn = bIn = cLoIn = cHiIn = true;
        portA = portB = portC = 0;
        applyPortC();
    }

    /** Clears all memory and resets (cold start). */
    public void hardReset() {
        progStart = progEnd = -1;
        java.util.Arrays.fill(ram, (byte) 0);
        java.util.Arrays.fill(colorRam, (byte) 0);
        cpu.a = cpu.b = cpu.c = cpu.d = cpu.e = cpu.h = cpu.l = 0;
        cpu.sp = 0;
        cpu.setF(0);
        reset();
    }

    // ------------------------------------------------------------------ bus

    /** Memory access tracing for the live memory map (off by default). */
    public final MemTrace trace = new MemTrace();

    /** Byte value without tracing or side effects (memory map, inspector). */
    public int rawRead(int addr) {
        if (addr < 0xC000) return ram[addr] & 0xFF;
        if (addr < 0xF000) return rom[addr - 0xC000] & 0xFF;
        if (addr >= 0xF800) return ppiRead(addr & 3);
        return 0xFF;
    }

    @Override
    public int read(int addr) {
        if (trace.enabled) trace.lastRead[addr] = trace.now;
        if (addr < 0xC000) return ram[addr] & 0xFF;
        if (addr < 0xF000) return rom[addr - 0xC000] & 0xFF;
        if (addr >= 0xF800) return ppiRead(addr & 3);
        return 0xFF;
    }

    @Override
    public int fetch(int addr) {
        if (trace.enabled) trace.lastExec[addr] = trace.now;
        if (addr < 0xC000) return ram[addr] & 0xFF;
        if (addr < 0xF000) return rom[addr - 0xC000] & 0xFF;
        if (addr >= 0xF800) return ppiRead(addr & 3);
        return 0xFF;
    }

    @Override
    public void write(int addr, int value) {
        if (trace.enabled) trace.lastWrite[addr] = trace.now;
        if (addr < 0xC000) {
            ram[addr] = (byte) value;
            if (addr >= 0x9000) colorRam[addr - 0x9000] = (byte) curColor;
        } else if (addr >= 0xF800) {
            ppiWrite(addr & 3, value);
        }
    }

    @Override
    public int in(int port) {
        return 0xFF;
    }

    @Override
    public void out(int port, int value) {
    }

    /** Direct memory peek/poke without side effects (debug, loaders). */
    public int peek(int addr) {
        return read(addr & 0xFFFF);
    }

    // ------------------------------------------------------------------ PPI 8255 (mode 0)

    private int selectedColumns() {
        int a = aIn ? 0xFF : portA;
        int c = cLoIn ? 0x0F : (portC & 0x0F);
        return ~(a | (c << 8)) & 0xFFF;
    }

    private int rowsForColumns(int cols) {
        int rows = 0;
        for (int j = 0; j < SpecKeys.COLS; j++)
            if ((cols & (1 << j)) != 0) rows |= keyCols.get(j);
        return rows;
    }

    private int columnsForRows(int rows) {
        int cols = 0;
        if (rows == 0) return 0;
        for (int j = 0; j < SpecKeys.COLS; j++)
            if ((keyCols.get(j) & rows) != 0) cols |= 1 << j;
        return cols;
    }

    private int ppiRead(int reg) {
        switch (reg) {
            case 0: {
                if (!aIn) return portA;
                int rows = bIn ? 0 : (~(portB >> 2)) & 0x3F;
                return ~columnsForRows(rows) & 0xFF;
            }
            case 1: {
                if (!bIn) return portB;
                int rows = rowsForColumns(selectedColumns());
                return ((~rows & 0x3F) << 2) | (shiftDown ? 0 : 2) | (tapeBit() ? 1 : 0);
            }
            case 2: {
                int v = portC & 0xF0;
                if (cHiIn) v = 0xF0;
                if (cLoIn) {
                    int rows = bIn ? 0 : (~(portB >> 2)) & 0x3F;
                    v |= (~columnsForRows(rows) >> 8) & 0x0F;
                } else {
                    v |= portC & 0x0F;
                }
                return v;
            }
            default:
                return 0xFF;
        }
    }

    private void ppiWrite(int reg, int v) {
        switch (reg) {
            case 0: portA = v; break;
            case 1: portB = v; break;
            case 2: portC = v; applyPortC(); break;
            default:
                if ((v & 0x80) != 0) {
                    aIn = (v & 0x10) != 0;
                    cHiIn = (v & 0x08) != 0;
                    bIn = (v & 0x02) != 0;
                    cLoIn = (v & 0x01) != 0;
                    portA = portB = portC = 0;
                } else {
                    int bit = (v >> 1) & 7;
                    if ((v & 1) != 0) portC |= 1 << bit;
                    else portC &= ~(1 << bit);
                }
                applyPortC();
                break;
        }
    }

    private void applyPortC() {
        int eff = cHiIn ? (portC | 0xF0) : portC;
        beeper = (eff & 0x20) != 0;
        boolean to = (eff & 0x80) != 0;
        if (to != tapeOut) {
            tapeOut = to;
            TapeOutListener l = tapeOutListener;
            if (l != null) l.onTapeEdge(cycleCount, to);
        }
        curColor = eff & 0xD0;
    }

    // ------------------------------------------------------------------ keyboard (UI thread)

    public synchronized void keyDown(int code) {
        if (code < 0 || code >= SpecKeys.COUNT) return;
        if (pressCount[code]++ == 0) setKey(code, true);
    }

    public synchronized void keyUp(int code) {
        if (code < 0 || code >= SpecKeys.COUNT) return;
        if (pressCount[code] > 0 && --pressCount[code] == 0) setKey(code, false);
    }

    public synchronized void releaseAllKeys() {
        java.util.Arrays.fill(pressCount, 0);
        for (int j = 0; j < SpecKeys.COLS; j++) keyCols.set(j, 0);
        shiftDown = false;
    }

    public boolean isKeyDown(int code) {
        if (code == SpecKeys.SHIFT) return shiftDown;
        if (code < 0 || code >= SpecKeys.SHIFT) return false;
        return (keyCols.get(code / SpecKeys.ROWS) & (1 << (code % SpecKeys.ROWS))) != 0;
    }

    private void setKey(int code, boolean down) {
        if (code == SpecKeys.SHIFT) {
            shiftDown = down;
            return;
        }
        int col = code / SpecKeys.ROWS, bit = 1 << (code % SpecKeys.ROWS);
        int v;
        do {
            v = keyCols.get(col);
        } while (!keyCols.compareAndSet(col, v, down ? (v | bit) : (v & ~bit)));
    }

    // ------------------------------------------------------------------ execution

    /**
     * Runs one 1/50 s frame. Audio samples (mono 16-bit, {@link #SAMPLE_RATE}) are written to
     * {@code audio} if not null; returns the number of samples produced.
     */
    public int runFrame(short[] audio) {
        return run(audio, CYCLES_PER_FRAME);
    }

    /** Runs {@code cycles} CPU cycles, writing audio samples to {@code audio}; returns sample count. */
    public int run(short[] audio, int cycles) {
        audioOut = audio;
        audioPos = 0;
        runCycles(cycles);
        audioOut = null;
        return audioPos;
    }

    public void runCycles(int cycles) {
        int done = 0;
        while (done < cycles) {
            int cyc = cpu.step();
            done += cyc;
            winCyc += cyc;
            cycleCount += cyc;
            if (beeper) winHi += cyc;
            if (tapeOut) winTapeOut += cyc;
            if (tapeInVolume > 0 && tapeBit()) winTapeIn += cyc;
            sampleAcc += (long) cyc * SAMPLE_RATE;
            while (sampleAcc >= CPU_HZ) {
                sampleAcc -= CPU_HZ;
                emitSample();
            }
        }
    }

    private void emitSample() {
        float x = 0f;
        if (winCyc > 0) {
            x = (float) winHi / winCyc;
            // tape signals go to the speaker too, like the real machine's tape monitor
            if (tapeOutVolume > 0) x += tapeOutVolume * winTapeOut / winCyc;
            if (tapeInVolume > 0) x += tapeInVolume * winTapeIn / winCyc;
        }
        winCyc = winHi = winTapeOut = winTapeIn = 0;
        // DC-blocking high-pass so a constant level is silent
        float y = x - hpPrevIn + 0.995f * hpPrevOut;
        hpPrevIn = x;
        hpPrevOut = y;
        if (audioOut != null && audioPos < audioOut.length) {
            float s = y * volume * 32767f;
            if (s > 32767f) s = 32767f;
            if (s < -32768f) s = -32768f;
            audioOut[audioPos++] = (short) s;
        }
    }

    /** current tape input bit as seen on port B bit 0 */
    private boolean tapeBit() {
        TapeInput in = tapeInput;
        if (in == null) return true; // no tape: line idles high, as before
        return in.level(cycleCount) != tapeInvert;
    }

    public long getCycleCount() {
        return cycleCount;
    }

    // ------------------------------------------------------------------ video

    /** Renders the current screen as ARGB pixels (WIDTH x HEIGHT). */
    public void render(int[] pixels, int colorMode) {
        int mono = MONO_INK[colorMode >= 0 && colorMode < 3 ? colorMode : 0];
        for (int col = 0; col < 48; col++) {
            int base = 0x9000 + col * 256;
            for (int line = 0; line < 256; line++) {
                int bt = ram[base + line] & 0xFF;
                int ink;
                if (colorMode == COLOR_8) {
                    int cb = colorRam[col * 256 + line] & 0xFF;
                    ink = PALETTE8[((cb & 0xC0) >> 5) | ((cb & 0x10) >> 4)];
                } else {
                    ink = mono;
                }
                int p = line * WIDTH + col * 8;
                for (int bitN = 0; bitN < 8; bitN++, bt <<= 1)
                    pixels[p + bitN] = (bt & 0x80) != 0 ? ink : PAPER;
            }
        }
    }

    // ------------------------------------------------------------------ program loading

    /** Result of parsing an .rks file. */
    public static final class RksInfo {
        public final int start, end;
        public final boolean checksumOk;

        RksInfo(int start, int end, boolean checksumOk) {
            this.start = start;
            this.end = end;
            this.checksumOk = checksumOk;
        }
    }

    /** Monitor-style checksum of a block (same algorithm as RK-86 / Specialist monitors). */
    static int checksum(byte[] d, int off, int len) {
        int cs = 0;
        for (int i = 0; i < len; i++) {
            int b = d[off + i] & 0xFF;
            cs += b;
            if (i != len - 1) cs += b << 8;
        }
        return cs & 0xFFFF;
    }

    /**
     * Loads an .rks tape image directly into memory. If {@code run} is set, the machine is
     * reset, the monitor is allowed to initialise, then execution jumps to the start address.
     *
     * @throws IOException if the file is not a valid program image
     */
    /** Address range of the last loaded program (for the memory inspector), -1 if none. */
    public volatile int progStart = -1, progEnd = -1;

    public RksInfo loadRks(byte[] data, boolean run) throws IOException {
        int p = 0, n = data.length;
        if (n > 0 && (data[0] & 0xFF) == 0xE6) p++;
        if (n - p >= 3 && (data[p] & 0xFF) == 0xD9 && (data[p + 1] & 0xFF) == 0xD9 && (data[p + 2] & 0xFF) == 0xD9) {
            p += 3;
            while (p < n && (data[p] & 0xFF) != 0xE6) p++;
            p++;
        }
        if (n - p < 5) throw new IOException("Файл слишком короткий");
        int beg = (data[p] & 0xFF) | ((data[p + 1] & 0xFF) << 8);
        int end = (data[p + 2] & 0xFF) | ((data[p + 3] & 0xFF) << 8);
        p += 4;
        int len = end - beg + 1;
        if (beg == 0xE6E6 || beg == 0xD3D3 || len <= 0 || n - p < len)
            throw new IOException("Это не программа в формате RKS");

        boolean csOk = true;
        if (n - p >= len + 2) {
            int cs = (data[p + len] & 0xFF) | ((data[p + len + 1] & 0xFF) << 8);
            csOk = cs == checksum(data, p, len);
        }

        if (run) {
            reset();
            runCycles(600_000); // let the monitor initialise
        }
        for (int i = 0; i < len; i++) write((beg + i) & 0xFFFF, data[p + i] & 0xFF);
        if (run) {
            releaseAllKeys();
            cpu.halted = false;
            cpu.pc = beg;
        }
        progStart = beg;
        progEnd = end;
        return new RksInfo(beg, end, csOk);
    }

    // ------------------------------------------------------------------ save states

    private static final int STATE_MAGIC = 0x53504543; // "SPEC"
    private static final int STATE_VERSION = 1;

    public byte[] saveState() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(0x10000);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(STATE_MAGIC);
            out.writeInt(STATE_VERSION);
            cpu.save(out);
            out.write(ram);
            out.write(colorRam);
            out.writeByte(portA);
            out.writeByte(portB);
            out.writeByte(portC);
            out.writeBoolean(aIn);
            out.writeBoolean(bIn);
            out.writeBoolean(cLoIn);
            out.writeBoolean(cHiIn);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void loadState(byte[] state) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(state));
        if (in.readInt() != STATE_MAGIC || in.readInt() != STATE_VERSION)
            throw new IOException("Неверный файл состояния");
        cpu.load(in);
        in.readFully(ram);
        in.readFully(colorRam);
        portA = in.readUnsignedByte();
        portB = in.readUnsignedByte();
        portC = in.readUnsignedByte();
        aIn = in.readBoolean();
        bIn = in.readBoolean();
        cLoIn = in.readBoolean();
        cHiIn = in.readBoolean();
        applyPortC();
        releaseAllKeys();
    }
}
