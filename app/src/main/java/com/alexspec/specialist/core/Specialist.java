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
    /**
     * The keys the machine sees (read by the PPI). Copied from the live keys above at the start
     * of every run slice; each change is logged with its time, so going back in time can replay
     * the same keys exactly.
     */
    private final int[] curCols = new int[SpecKeys.COLS];
    private boolean curShift;
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
        clearHistory();
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
        if (trace.enabled) trace.lastRead[addr] = stamp;
        if (addr < 0xC000) return ram[addr] & 0xFF;
        if (addr < 0xF000) return rom[addr - 0xC000] & 0xFF;
        if (addr >= 0xF800) return ppiRead(addr & 3);
        return 0xFF;
    }

    @Override
    public int fetch(int addr) {
        if (trace.enabled) trace.lastExec[addr] = stamp;
        if (addr < 0xC000) return ram[addr] & 0xFF;
        if (addr < 0xF000) return rom[addr - 0xC000] & 0xFF;
        if (addr >= 0xF800) return ppiRead(addr & 3);
        return 0xFF;
    }

    @Override
    public void write(int addr, int value) {
        if (trace.enabled) trace.lastWrite[addr] = stamp;
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
            if ((cols & (1 << j)) != 0) rows |= curCols[j];
        return rows;
    }

    private int columnsForRows(int rows) {
        int cols = 0;
        if (rows == 0) return 0;
        for (int j = 0; j < SpecKeys.COLS; j++)
            if ((curCols[j] & rows) != 0) cols |= 1 << j;
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
                return ((~rows & 0x3F) << 2) | (curShift ? 0 : 2) | (tapeBit() ? 1 : 0);
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
            if (l != null && !replaying()) l.onTapeEdge(cycleCount, to);
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
        syncInput();
        long end = cycleCount + cycles;
        while (cycleCount < end) stepOne();
    }

    /** One instruction: history point, keys, trace stamp, CPU, sound. */
    private void stepOne() {
        if (historyOn && cycleCount >= nextSnapAt) pushSnap();
        if (evPos < events.size()) replayInput();
        stamp = cycleCount + 1;
        lastInstrStart = cycleCount;
        lastInstrPc = cpu.pc;
        {
            int cyc = cpu.step();
            instr++;
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

    // ------------------------------------------------------------------ time control

    /** time stamp given to the memory accesses of the current instruction (its start + 1) */
    private long stamp;
    /** instructions executed since the program was started (the unit of single steps) */
    public long instr;
    /** address and start time of the last executed instruction (pause screen, memory map) */
    public volatile int lastInstrPc = -1;
    public volatile long lastInstrStart = -1;

    /** emulated time in CPU cycles */
    public long time() {
        return cycleCount;
    }

    /** Executes exactly one instruction. */
    public void stepInstruction() {
        syncInput();
        stepOne();
    }

    /** Runs forward {@code t} cycles (whole instructions), no sound. */
    public void forward(long t) {
        syncInput();
        long end = cycleCount + t;
        while (cycleCount < end) stepOne();
    }

    /** Copies the live keys into the machine; logs a change (only outside a replay). */
    private void syncInput() {
        if (replaying()) return;
        boolean changed = shiftDown != curShift;
        for (int j = 0; j < SpecKeys.COLS && !changed; j++) changed = keyCols.get(j) != curCols[j];
        if (!changed) return;
        for (int j = 0; j < SpecKeys.COLS; j++) curCols[j] = keyCols.get(j);
        curShift = shiftDown;
        if (historyOn) {
            KeyEvent e = new KeyEvent(cycleCount, curCols, curShift);
            // a change at the same moment replaces the previous one
            if (!events.isEmpty() && events.get(events.size() - 1).time == cycleCount) events.remove(events.size() - 1);
            events.add(e);
            evPos = events.size();
        }
    }

    /** While replaying: apply the logged key changes that happened by now. */
    private void replayInput() {
        while (evPos < events.size() && events.get(evPos).time <= cycleCount) {
            KeyEvent e = events.get(evPos++);
            System.arraycopy(e.cols, 0, curCols, 0, SpecKeys.COLS);
            curShift = e.shift;
        }
    }

    /**
     * true while re-running time that was already played: the keys come from the log (also at
     * the very moment the replay reaches the point where we went back from).
     */
    private boolean replaying() {
        return cycleCount < recordedEnd || evPos < events.size();
    }

    private static final class KeyEvent {
        final long time;
        final int[] cols;
        final boolean shift;

        KeyEvent(long time, int[] cols, boolean shift) {
            this.time = time;
            this.cols = cols.clone();
            this.shift = shift;
        }
    }

    /** machine state at an instruction boundary near a frame start */
    private static final class Snap {
        final byte[] ram = new byte[0xC000];
        final byte[] colorRam = new byte[0x3000];
        final int[] regs = new int[24];
        final int[] cols = new int[SpecKeys.COLS];
        long cycle, instr;
    }

    /** a snapshot every 10 frames (0,2 s) */
    private static final int SNAP_CYCLES = CYCLES_PER_FRAME * 10;
    /** history kept for going back: 310 snapshots = 62 s, at least a minute (about 19 MB) */
    public static final int HISTORY = 310;
    private final Snap[] hist = new Snap[HISTORY];
    private int histHead, histCount;
    private final java.util.ArrayList<KeyEvent> events = new java.util.ArrayList<>();
    /** index of the first logged key change not yet applied */
    private int evPos;
    /** the furthest time reached; before it, time is being replayed */
    private long recordedEnd;
    private long nextSnapAt;
    /** history is off while the microphone feeds the tape input (it cannot be replayed) */
    private boolean historyOn = true;

    private Snap snapAt(int k) { // k = 0 newest
        return hist[(histHead + HISTORY - 1 - k) % HISTORY];
    }

    private void pushSnap() {
        Snap s = hist[histHead];
        if (s == null) s = hist[histHead] = new Snap();
        histHead = (histHead + 1) % HISTORY;
        if (histCount < HISTORY) histCount++;
        System.arraycopy(ram, 0, s.ram, 0, ram.length);
        System.arraycopy(colorRam, 0, s.colorRam, 0, colorRam.length);
        I8080 c = cpu;
        int[] r = s.regs;
        int i = 0;
        for (int v : new int[]{c.pc, c.sp, c.a, c.getF(), c.b, c.c, c.d, c.e, c.h, c.l, c.halted ? 1 : 0,
            c.inte ? 1 : 0, c.pendingEi, portA, portB, portC, aIn ? 1 : 0, bIn ? 1 : 0, cLoIn ? 1 : 0,
            cHiIn ? 1 : 0, curShift ? 1 : 0}) r[i++] = v;
        System.arraycopy(curCols, 0, s.cols, 0, SpecKeys.COLS);
        s.cycle = cycleCount;
        s.instr = instr;
        nextSnapAt = (cycleCount / SNAP_CYCLES + 1) * SNAP_CYCLES;
        // key changes older than the oldest snapshot are no longer needed
        if (histCount == HISTORY && !events.isEmpty()) {
            long oldest = snapAt(HISTORY - 1).cycle;
            int drop = 0;
            while (drop < events.size() && events.get(drop).time <= oldest) drop++;
            if (drop > 64) {
                events.subList(0, drop).clear();
                evPos = Math.max(0, evPos - drop);
            }
        }
    }

    private void restore(Snap s) {
        System.arraycopy(s.ram, 0, ram, 0, ram.length);
        System.arraycopy(s.colorRam, 0, colorRam, 0, colorRam.length);
        I8080 c = cpu;
        int[] r = s.regs;
        c.pc = r[0]; c.sp = r[1]; c.a = r[2]; c.setF(r[3]); c.b = r[4]; c.c = r[5]; c.d = r[6]; c.e = r[7];
        c.h = r[8]; c.l = r[9]; c.halted = r[10] != 0; c.inte = r[11] != 0; c.pendingEi = r[12];
        portA = r[13]; portB = r[14]; portC = r[15];
        aIn = r[16] != 0; bIn = r[17] != 0; cLoIn = r[18] != 0; cHiIn = r[19] != 0; curShift = r[20] != 0;
        System.arraycopy(s.cols, 0, curCols, 0, SpecKeys.COLS);
        cycleCount = s.cycle;
        instr = s.instr;
        applyPortC(); // colour, beeper, tape out from port C (no tape event: we are replaying)
        lastInstrPc = -1;
        lastInstrStart = -1;
        // the keys logged after this point are replayed
        int lo = 0, hi = events.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (events.get(mid).time <= cycleCount) lo = mid + 1;
            else hi = mid;
        }
        evPos = lo;
        // snapshots after this point are made again by the replay
        while (histCount > 1 && snapAt(0).cycle > cycleCount) {
            histHead = (histHead + HISTORY - 1) % HISTORY;
            histCount--;
        }
        nextSnapAt = (cycleCount / SNAP_CYCLES + 1) * SNAP_CYCLES;
    }

    /** latest snapshot at or before both targets; the oldest one if none; null if no history */
    private Snap findSnap(long targetInstr, long targetCycle) {
        Snap best = null;
        for (int k = 0; k < histCount; k++) {
            Snap s = snapAt(k);
            best = s;
            if (s.instr <= targetInstr && s.cycle <= targetCycle) return s;
        }
        return best;
    }

    private boolean goBack(long targetInstr, long targetCycle) {
        Snap s = findSnap(targetInstr, targetCycle);
        if (s == null) return false;
        recordedEnd = Math.max(recordedEnd, cycleCount);
        boolean ok = s.instr <= targetInstr && s.cycle <= targetCycle;
        restore(s);
        audioOut = null;
        while (instr < targetInstr && cycleCount < targetCycle) stepOne();
        return ok;
    }

    /** Goes back {@code n} instructions; false if the history does not reach (the oldest point is kept). */
    public boolean backInstructions(long n) {
        return goBack(instr - n, Long.MAX_VALUE);
    }

    /** Goes back {@code t} cycles (to an instruction boundary); false if the history does not reach. */
    public boolean backTime(long t) {
        return goBack(Long.MAX_VALUE, cycleCount - t);
    }

    /** After going back, going forward replays what happened; this makes the live keys count again. */
    public void forgetFuture() {
        if (!replaying()) return;
        recordedEnd = cycleCount;
        while (events.size() > evPos) events.remove(events.size() - 1);
    }

    /** seconds of history available */
    public float historySeconds() {
        if (histCount == 0) return 0;
        return (cycleCount - snapAt(histCount - 1).cycle) / (float) CPU_HZ;
    }

    /** Forgets the history (new program, loaded state, microphone input). */
    public void clearHistory() {
        histCount = 0;
        histHead = 0;
        events.clear();
        evPos = 0;
        recordedEnd = 0;
        nextSnapAt = cycleCount;
    }

    /** History is kept only when the tape input is not live (the microphone cannot be replayed). */
    public void setHistoryEnabled(boolean on) {
        if (on == historyOn) return;
        historyOn = on;
        clearHistory();
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
        clearHistory();
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
        java.util.Arrays.fill(curCols, 0);
        curShift = false;
        clearHistory();
    }
}
