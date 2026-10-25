package com.alexspec.zx.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * The ZX Spectrum 48K.
 *
 * 0000-3FFF ROM, 4000-FFFF RAM (4000-7FFF shared with the ULA, contended).
 * Frame: 312 lines x 224 T = 69888 T at 3.5 MHz (50.08 Hz); the INT line is active for the first
 * 32 T. The first pixel of the screen is fetched at T 14336 (line 64); contention starts one
 * T earlier and follows the pattern 6,5,4,3,2,1,0,0 during the 128 T of each of the 192 lines.
 * Port FE (any even port): in = keyboard half-rows (A8-A15 select) on bits 0-4, EAR on bit 6;
 * out = border (bits 0-2), MIC (bit 3), EAR/beeper (bit 4). Kempston joystick on port 1F.
 */
public final class Spectrum48 implements Z80.Bus, MemTrace.Mem {
    public static final int CPU_HZ = 3_500_000;
    public static final int T_LINE = 224, LINES = 312, T_FRAME = T_LINE * LINES; // 69888
    public static final int SAMPLE_RATE = 44100;
    /** displayed area: 256x192 screen with a 32 pixel border all round */
    public static final int BORDER = 32, WIDTH = 256 + 2 * BORDER, HEIGHT = 192 + 2 * BORDER;
    private static final int FIRST_LINE = 64 - BORDER; // first displayed scan line
    private static final int CONTEND_START = 14335;

    public static final int[] PALETTE = {
        0xFF000000, 0xFF0000D7, 0xFFD70000, 0xFFD700D7, 0xFF00D700, 0xFF00D7D7, 0xFFD7D700, 0xFFD7D7D7,
        0xFF000000, 0xFF0000FF, 0xFFFF0000, 0xFFFF00FF, 0xFF00FF00, 0xFF00FFFF, 0xFFFFFF00, 0xFFFFFFFF,
    };

    private static final byte[] DELAY = new byte[T_FRAME + 64];

    static {
        for (int t = CONTEND_START; t < CONTEND_START + 192 * T_LINE; t++) {
            int pos = (t - CONTEND_START) % T_LINE;
            if (pos < 128) {
                int d = 6 - (pos & 7);
                DELAY[t] = (byte) (d < 0 ? 0 : d);
            }
        }
    }

    private final byte[] mem = new byte[0x10000];
    public final Z80 cpu = new Z80(this);
    public final MemTrace trace = new MemTrace();

    // ULA
    private int border = 7;
    private boolean ear, mic;
    private int frameCount;
    /** border changes during the current frame: time and colour */
    private int[] bLogT = new int[4096], bLogC = new int[4096];
    private int bLogN, bLogPos, rowBorder, frameStartBorder = 7;
    private int nextRow;
    private int[] pixels;

    // keyboard: 8 half-rows, bit per key (1 = pressed), from the UI thread
    private final AtomicIntegerArray rows = new AtomicIntegerArray(8);
    private final int[] pressCount = new int[45];
    /** keys pressed by the automatic typist (auto LOAD "") */
    private final int[] scriptRows = new int[8];
    private int[][] script;
    private int scriptPos, scriptTimer;
    /** Kempston joystick bits: 1 right, 2 left, 4 down, 8 up, 16 fire */
    public volatile int kempston;

    // tape
    public volatile Tape tape;
    /** instant loading through the ROM routine (only for standard blocks) */
    public volatile boolean flashLoad = true;
    /** live tape input (microphone), used when no tape file is playing */
    public volatile TapeInput tapeInput;
    public volatile boolean tapeInvert;
    /** loudness in the speaker of the tape being played / of the MIC output (SAVE), 0 = mute */
    public volatile float tapeVolume = 0.25f, micVolume = 0.5f;
    private long cycleCount;
    /** set when the ROM loader is waiting for the tape (for the UI and fast-forward) */
    public volatile boolean loading;
    private int romLoaderFrames;

    // audio
    private long sampleAcc;
    private int winT, winHi, winTape, winMic;
    private float hpPrevIn, hpPrevOut;
    private short[] audioOut;
    private int audioPos;
    public float volume = 0.4f;

    /** address range of the last loaded program (memory inspector); -1 = unknown */
    public volatile int progStart = -1, progEnd = -1;

    public Spectrum48(byte[] rom) {
        System.arraycopy(rom, 0, mem, 0, Math.min(rom.length, 0x4000));
        hardReset();
    }

    // ------------------------------------------------------------------ reset

    public void reset() {
        cpu.reset();
        cpu.t = 0;
        border = frameStartBorder = 7;
        bLogN = 0;
    }

    /** Clears the RAM (like switching off) and resets. */
    public void hardReset() {
        java.util.Arrays.fill(mem, 0x4000, 0x10000, (byte) 0);
        progStart = progEnd = -1;
        script = null;
        java.util.Arrays.fill(scriptRows, 0);
        reset();
        frameOpen = false;
        histCount = 0;
    }

    // ------------------------------------------------------------------ bus

    @Override
    public int rawRead(int addr) {
        return mem[addr & 0xFFFF] & 0xFF;
    }

    public void poke(int addr, int v) {
        addr &= 0xFFFF;
        if (addr >= 0x4000) mem[addr] = (byte) v;
    }

    @Override
    public int read(int addr) {
        if (trace.enabled) trace.lastRead[addr] = (long) frameCount * T_FRAME + cpu.t;
        return mem[addr] & 0xFF;
    }

    @Override
    public int fetch(int addr) {
        if (trace.enabled) trace.lastExec[addr] = (long) frameCount * T_FRAME + cpu.t;
        return mem[addr] & 0xFF;
    }

    @Override
    public int arg(int addr) {
        if (trace.enabled) trace.lastExec[addr] = (long) frameCount * T_FRAME + cpu.t;
        return mem[addr] & 0xFF;
    }

    @Override
    public void skipped(int addr) {
        if (trace.enabled) trace.lastExec[addr] = (long) frameCount * T_FRAME + cpu.t;
    }

    @Override
    public void write(int addr, int v) {
        if (addr < 0x4000) return;
        if (trace.enabled) trace.lastWrite[addr] = (long) frameCount * T_FRAME + cpu.t;
        mem[addr] = (byte) v;
    }

    @Override
    public boolean contended(int addr) {
        return (addr & 0xC000) == 0x4000;
    }

    @Override
    public int delay(long t) {
        return t >= 0 && t < DELAY.length ? DELAY[(int) t] : 0;
    }

    @Override
    public int in(int port) {
        if ((port & 1) == 0) {
            int hi = port >> 8, keys = 0;
            for (int r = 0; r < 8; r++)
                if ((hi & (1 << r)) == 0) keys |= (replaying ? replayKeys[r] : rows.get(r)) | scriptRows[r];
            return 0xA0 | (~keys & 0x1F) | (earIn() ? 0x40 : 0);
        }
        if ((port & 0x20) == 0) return (replaying ? replayKeys[8] : kempston) & 0x1F;
        return floatingBus();
    }

    @Override
    public void out(int port, int v) {
        if ((port & 1) != 0) return;
        int nb = v & 7;
        if (nb != border) {
            border = nb;
            if (bLogN == bLogT.length) {
                bLogT = java.util.Arrays.copyOf(bLogT, bLogN * 2);
                bLogC = java.util.Arrays.copyOf(bLogC, bLogN * 2);
            }
            bLogT[bLogN] = (int) cpu.t;
            bLogC[bLogN++] = nb;
        }
        ear = (v & 0x10) != 0;
        mic = (v & 0x08) != 0;
    }

    /** what the ULA is reading from video memory right now (idle bus), 0xFF outside the screen */
    private int floatingBus() {
        int t = (int) cpu.t - (CONTEND_START + 3);
        if (t < 0 || t >= 192 * T_LINE) return 0xFF;
        int y = t / T_LINE, pos = t % T_LINE;
        if (pos >= 128) return 0xFF;
        int col = (pos >> 3) * 2;
        switch (pos & 7) {
            case 0: return mem[pixelAddr(y, col)] & 0xFF;
            case 1: return mem[0x5800 + (y >> 3) * 32 + col] & 0xFF;
            case 2: return mem[pixelAddr(y, col + 1)] & 0xFF;
            case 3: return mem[0x5800 + (y >> 3) * 32 + col + 1] & 0xFF;
            default: return 0xFF;
        }
    }

    public static int pixelAddr(int y, int col) {
        return 0x4000 | ((y & 0xC0) << 5) | ((y & 7) << 8) | ((y & 0x38) << 2) | col;
    }

    private boolean earIn() {
        Tape tp = tape;
        if (tp != null && tp.playing) return tp.level();
        TapeInput in = tapeInput;
        if (in != null) return in.level(cycleCount) != tapeInvert;
        return ear; // issue 3: the EAR output is seen on the input
    }

    // ------------------------------------------------------------------ keyboard

    /** Key code = half-row * 5 + bit (half-rows in port order FEFE..7FFE). */
    public static final int CAPS = 0, SYM = 36, ENTER = 30, SPACE = 35;

    /** virtual keys 40..44: Kempston right, left, down, up, fire */
    public static final int KEMPSTON = 40, KEY_COUNT = 45;

    public synchronized void keyDown(int code) {
        if (code < 0 || code >= KEY_COUNT) return;
        if (pressCount[code]++ == 0) setKey(code, true);
    }

    public synchronized void keyUp(int code) {
        if (code < 0 || code >= KEY_COUNT) return;
        if (pressCount[code] > 0 && --pressCount[code] == 0) setKey(code, false);
    }

    public synchronized void releaseAllKeys() {
        java.util.Arrays.fill(pressCount, 0);
        for (int r = 0; r < 8; r++) rows.set(r, 0);
        kempston = 0;
    }

    public boolean isKeyDown(int code) {
        if (code >= KEMPSTON) return (kempston & (1 << (code - KEMPSTON))) != 0;
        return (rows.get(code / 5) & (1 << (code % 5))) != 0;
    }

    private void setKey(int code, boolean down) {
        if (code >= KEMPSTON) {
            int bit = 1 << (code - KEMPSTON);
            kempston = down ? kempston | bit : kempston & ~bit;
            return;
        }
        int r = code / 5, bit = 1 << (code % 5), v;
        do {
            v = rows.get(r);
        } while (!rows.compareAndSet(r, v, down ? v | bit : v & ~bit));
    }

    /** Types key combinations one after another (each held 4 frames, then released 8 frames: the ROM ignores a repeated key released for less than 5 interrupts). */
    public void type(int[][] combos) {
        script = combos;
        scriptPos = 0;
        scriptTimer = 0;
    }

    public boolean typing() {
        return script != null;
    }

    private void scriptFrame() {
        if (script == null) return;
        java.util.Arrays.fill(scriptRows, 0);
        if (scriptPos >= script.length) {
            script = null;
            return;
        }
        if (scriptTimer < 4) for (int k : script[scriptPos]) scriptRows[k / 5] |= 1 << (k % 5);
        if (++scriptTimer == 12) {
            scriptTimer = 0;
            scriptPos++;
        }
    }

    // ------------------------------------------------------------------ execution

    /** the picture (WIDTH x HEIGHT ARGB); rows are drawn as the beam passes them */
    public final int[] screen = new int[WIDTH * HEIGHT];
    private boolean frameOpen, wasLoading;
    /** completed frames since power-on (also drives FLASH) */
    public int frames() {
        return frameCount;
    }

    /** instructions executed since power-on (the unit of single-stepping) */
    public long instr;
    /** address and start time of the last executed instruction (for single-stepping display) */
    public volatile int lastInstrPc = -1;
    public volatile long lastInstrStart;
    /** T-states executed by the last {@link #run} call */
    public long lastRunT;

    private void beginFrame() {
        nextRow = 0;
        bLogPos = 0;
        rowBorder = frameStartBorder;
        scriptFrame();
        wasLoading = false;
        frameOpen = true;
        if (historyOn) pushHistory();
        if (replaying) {
            // use the keys that were held when this frame really ran
            long now = time();
            for (int k = 0; k < histCount; k++) {
                Snap h = hist[(histHead + HISTORY - 1 - k) % HISTORY];
                if (h.abs <= now) {
                    System.arraycopy(h.keys, 0, replayKeys, 0, 9);
                    break;
                }
            }
        }
    }

    private boolean replaying;
    private final int[] replayKeys = new int[9];

    /** re-executes up to the target, then forgets the history after it */
    private void replay(long targetInstr, long targetAbs) {
        replaying = true;
        pixels = screen;
        try {
            while (instr < targetInstr && time() < targetAbs) stepOne();
        } finally {
            replaying = false;
            pixels = null;
        }
        long now = time();
        while (histCount > 0 && hist[(histHead + HISTORY - 1) % HISTORY].abs > now) {
            histHead = (histHead + HISTORY - 1) % HISTORY;
            histCount--;
        }
    }

    private void endFrame() {
        while (nextRow < HEIGHT) renderRow(nextRow++);
        cpu.t -= T_FRAME;
        // carry the border colour into the next frame
        bLogN = 0;
        frameStartBorder = border;
        frameCount++;
        if (wasLoading) romLoaderFrames = 25;
        else if (romLoaderFrames > 0) romLoaderFrames--;
        loading = romLoaderFrames > 0;
        frameOpen = false;
    }

    /** one instruction (plus an interrupt it may take first); returns the T-states used */
    private long stepOne() {
        if (!frameOpen) beginFrame();
        long t0 = cpu.t;
        lastInstrStart = time();
        if (cpu.t < 32) cpu.interrupt();
        int pc = cpu.pc;
        boolean trapped = false;
        if (pc == 0x0556) {
            wasLoading = true;
            trapped = romLoad();
        } else if (pc >= 0x05E3 && pc < 0x0605) {
            wasLoading = true; // inside LD-EDGE: waiting for the tape
        }
        lastInstrPc = cpu.pc;
        if (!trapped) {
            long t1 = cpu.t;
            cpu.step();
            int dt = (int) (cpu.t - t1);
            Tape tp = tape;
            if (tp != null) tp.advance(dt);
            cycleCount += dt;
            audioStep(dt);
        }
        instr++;
        long used = cpu.t - t0;
        while (nextRow < HEIGHT && cpu.t >= rowDue(nextRow)) renderRow(nextRow++);
        if (cpu.t >= T_FRAME) endFrame();
        return used;
    }

    /**
     * Runs about {@code budget} T-states (any number, frames continue across calls), drawing into
     * {@link #screen} if {@code render}. Audio samples go to {@code audio} (may be null);
     * returns the number of samples written. With {@code toFrameEnd} it stops exactly at the end
     * of the current frame instead.
     */
    public int run(short[] audio, long budget, boolean render, boolean toFrameEnd) {
        pixels = render ? screen : null;
        audioOut = audio;
        audioPos = 0;
        long done = 0;
        int f0 = frameCount;
        while (toFrameEnd ? frameCount == f0 : done < budget) done += stepOne();
        lastRunT = done;
        pixels = null;
        audioOut = null;
        return audioPos;
    }

    /** Runs to the end of the current frame (a whole frame when called on a frame boundary). */
    public int runFrame(short[] audio) {
        return run(audio, 0, true, true);
    }

    /** Runs frames without rendering or audio (fast-forward). */
    public void runFast(int n) {
        for (int k = 0; k < n; k++) run(null, 0, false, true);
    }

    /** absolute emulated time in T-states since power-on */
    public long time() {
        return (long) frameCount * T_FRAME + cpu.t;
    }

    /** Executes exactly one instruction, drawing the beam's progress. */
    public void stepInstruction() {
        pixels = screen;
        stepOne();
        pixels = null;
    }

    /** Runs forward {@code t} T-states (whole instructions) with drawing, no sound. */
    public void forward(long t) {
        long end = time() + t;
        pixels = screen;
        while (time() < end) stepOne();
        pixels = null;
    }

    // ------------------------------------------------------------------ history (going back)

    /** machine state at the start of a frame */
    private static final class Snap {
        final byte[] ram = new byte[0xC000];
        final int[] regs = new int[40];
        /** keys held during this frame (8 half-rows + Kempston), replayed when going back */
        final int[] keys = new int[9];
        long instr, cycles, abs;
        Tape tape;
        long[] tapeState;
        int[][] script;
        final int[] scriptRows = new int[8];
    }

    /** frames kept for going back (10 s) */
    public static final int HISTORY = 500;
    private final Snap[] hist = new Snap[HISTORY];
    private int histHead, histCount;
    public volatile boolean historyOn = true;

    private void pushHistory() {
        if (replaying) return;
        if (histCount > 0) {
            Snap last = hist[(histHead + HISTORY - 1) % HISTORY];
            if (last.instr == instr) return; // already have this point (after a restore)
        }
        Snap s = hist[histHead];
        if (s == null) s = hist[histHead] = new Snap();
        histHead = (histHead + 1) % HISTORY;
        if (histCount < HISTORY) histCount++;
        System.arraycopy(mem, 0x4000, s.ram, 0, 0xC000);
        Z80 c = cpu;
        int[] r = s.regs;
        int i = 0;
        for (int v : new int[]{c.a, c.f, c.b, c.c, c.d, c.e, c.h, c.l, c.a_, c.f_, c.b_, c.c_, c.d_, c.e_, c.h_, c.l_,
            c.ix, c.iy, c.sp, c.pc, c.i, c.getR(), c.memptr, c.im, c.iff1 ? 1 : 0, c.iff2 ? 1 : 0, c.halted ? 1 : 0,
            c.eiDelay ? 1 : 0, (int) c.t, border, ear ? 1 : 0, mic ? 1 : 0, frameStartBorder, frameCount,
            romLoaderFrames, progStart, progEnd, scriptPos, scriptTimer}) r[i++] = v;
        for (int k = 0; k < 8; k++) s.keys[k] = rows.get(k);
        s.keys[8] = kempston;
        s.instr = instr;
        s.cycles = cycleCount;
        s.abs = time();
        s.tape = tape;
        s.tapeState = tape != null ? tape.saveState() : null;
        s.script = script;
        System.arraycopy(scriptRows, 0, s.scriptRows, 0, 8);
    }

    private void restore(Snap s) {
        System.arraycopy(s.ram, 0, mem, 0x4000, 0xC000);
        Z80 c = cpu;
        int[] r = s.regs;
        c.a = r[0]; c.f = r[1]; c.b = r[2]; c.c = r[3]; c.d = r[4]; c.e = r[5]; c.h = r[6]; c.l = r[7];
        c.a_ = r[8]; c.f_ = r[9]; c.b_ = r[10]; c.c_ = r[11]; c.d_ = r[12]; c.e_ = r[13]; c.h_ = r[14]; c.l_ = r[15];
        c.ix = r[16]; c.iy = r[17]; c.sp = r[18]; c.pc = r[19]; c.i = r[20]; c.setR(r[21]); c.memptr = r[22]; c.im = r[23];
        c.iff1 = r[24] != 0; c.iff2 = r[25] != 0; c.halted = r[26] != 0; c.eiDelay = r[27] != 0; c.t = r[28];
        border = r[29]; ear = r[30] != 0; mic = r[31] != 0; frameStartBorder = r[32]; frameCount = r[33];
        romLoaderFrames = r[34]; progStart = r[35]; progEnd = r[36]; scriptPos = r[37]; scriptTimer = r[38];
        instr = s.instr;
        cycleCount = s.cycles;
        tape = s.tape;
        if (s.tape != null && s.tapeState != null) s.tape.loadState(s.tapeState);
        script = s.script;
        System.arraycopy(s.scriptRows, 0, scriptRows, 0, 8);
        bLogN = 0;
        frameOpen = false;
        renderStatic();
    }

    /** latest snapshot at or before the condition; null if none */
    private Snap findSnap(long targetInstr, long targetAbs) {
        Snap best = null;
        for (int k = 0; k < histCount; k++) {
            Snap s = hist[(histHead + HISTORY - 1 - k) % HISTORY];
            if (s.instr <= targetInstr && s.abs <= targetAbs) return s;
            best = s; // oldest seen so far
        }
        return best == null ? null : best;
    }

    /**
     * Goes back {@code n} instructions; returns false if the history does not reach that far
     * (then the oldest kept point is restored).
     */
    public boolean backInstructions(long n) {
        long target = instr - n;
        Snap s = findSnap(target, Long.MAX_VALUE);
        if (s == null) return false;
        restore(s);
        replay(target, Long.MAX_VALUE);
        return s.instr <= target;
    }

    /** Goes back {@code t} T-states of emulated time (to an instruction boundary). */
    public boolean backTime(long t) {
        long target = time() - t;
        Snap s = findSnap(Long.MAX_VALUE, target);
        if (s == null) return false;
        restore(s);
        boolean ok = s.abs <= target;
        if (ok) replay(Long.MAX_VALUE, target);
        return ok;
    }

    /** seconds of history currently available */
    public float historySeconds() {
        if (histCount == 0) return 0;
        Snap oldest = hist[(histHead + HISTORY - histCount) % HISTORY];
        return (time() - oldest.abs) / (float) CPU_HZ;
    }

    public void clearHistory() {
        histCount = 0;
    }

    /** where the beam is now, in screen pixels {x, y}; null during vertical blanking */
    public int[] beam() {
        int t = (int) cpu.t;
        int line = t / T_LINE, pos = t % T_LINE;
        int x;
        if (pos < 144) x = BORDER + pos * 2;
        else if (pos >= 208) {
            x = BORDER - (T_LINE - pos) * 2;
            line++;
        } else x = WIDTH - 1; // horizontal retrace: park at the right edge
        int row = line - FIRST_LINE;
        if (row < 0 || row >= HEIGHT) return null;
        return new int[]{Math.max(0, Math.min(WIDTH - 1, x)), row};
    }

    private static int rowDue(int row) {
        return (FIRST_LINE + row) * T_LINE + 128 + 24; // after the right border of that line
    }

    /**
     * Trap at LD-BYTES (0556): if the next tape block is a standard one, copy it straight into
     * memory and return through LD-RET. Returns true if handled.
     */
    private boolean romLoad() {
        Tape tp = tape;
        if (tp == null) return false;
        int bi = tp.nextBlock();
        if (bi < 0) return false;
        if (!flashLoad) {
            tp.playing = true; // auto-play when the ROM starts loading
            return false;
        }
        byte[] d = tp.blocks.get(bi).data;
        int want = cpu.a, len = cpu.de(), dest = cpu.ix;
        boolean load = (cpu.f & Z80.FC) != 0;
        boolean ok = d.length > 0 && (d[0] & 0xFF) == want;
        if (ok) {
            int x = d[0] & 0xFF, n = Math.min(len, d.length - 1);
            for (int k = 0; k < n; k++) {
                int v = d[1 + k] & 0xFF;
                if (load) write((dest + k) & 0xFFFF, v);
                else if (rawRead((dest + k) & 0xFFFF) != v) ok = false;
                x ^= v;
            }
            if (d.length - 1 > n) x ^= d[1 + n] & 0xFF; else ok = false; // checksum byte
            ok &= x == 0 && n == len;
            cpu.ix = (dest + n) & 0xFFFF;
            cpu.setDE(len - n);
            if (load && ok && n > 0 && want == 0xFF) {
                progStart = dest;
                progEnd = (dest + n - 1) & 0xFFFF;
            }
        }
        tp.skipPast(bi);
        // keep the tape running for custom loaders that may follow
        tp.playing = !tp.finished();
        cpu.f = ok ? (cpu.f | Z80.FC) : (cpu.f & ~Z80.FC);
        cpu.pc = 0x053F; // LD-RET: restores the border, checks BREAK, EI, returns
        cpu.t += 100;
        return true;
    }

    // ------------------------------------------------------------------ audio

    private void audioStep(int dt) {
        winT += dt;
        if (ear) winHi += dt;
        if (mic) winMic += dt;
        if (tapeVolume > 0) {
            Tape tp = tape;
            boolean lvl = tp != null && tp.playing ? tp.level() : tapeInput != null && earIn();
            if (lvl) winTape += dt;
        }
        sampleAcc += (long) dt * SAMPLE_RATE;
        while (sampleAcc >= CPU_HZ) {
            sampleAcc -= CPU_HZ;
            float x = 0;
            if (winT > 0) x = ((float) winHi + tapeVolume * winTape + micVolume * winMic) / winT;
            winT = winHi = winTape = winMic = 0;
            float y = x - hpPrevIn + 0.995f * hpPrevOut;
            hpPrevIn = x;
            hpPrevOut = y;
            if (audioOut != null && audioPos < audioOut.length) {
                float s = y * volume * 32767f;
                audioOut[audioPos++] = (short) Math.max(-32768f, Math.min(32767f, s));
            }
        }
    }

    // ------------------------------------------------------------------ video

    private int borderAt(int t) {
        while (bLogPos < bLogN && bLogT[bLogPos] <= t) rowBorder = bLogC[bLogPos++];
        return rowBorder;
    }

    private void renderRow(int row) {
        int[] px = pixels;
        int line = FIRST_LINE + row, t0 = line * T_LINE;
        if (px == null) {
            borderAt(t0 + 160);
            return;
        }
        int p = row * WIDTH;
        int y = row - BORDER;
        boolean screen = y >= 0 && y < 192;
        // left border: x = -32..-1 -> t = t0 - 16 .. t0 - 1 (4 T per 8 pixels)
        for (int x = -BORDER; x < 256 + BORDER; x += 8) {
            if (screen && x >= 0 && x < 256) continue;
            int c = PALETTE[borderAt(t0 + (x >> 1))];
            int q = p + x + BORDER;
            for (int k = 0; k < 8; k++) px[q + k] = c;
        }
        if (screen) drawPaper(px, p, y);
        borderAt(t0 + 160);
    }

    /** draws the 256 screen pixels of line y (0..191) at px[p + BORDER ...] */
    private void drawPaper(int[] px, int p, int y) {
        boolean flash = (frameCount & 16) != 0;
        int ab = 0x5800 + (y >> 3) * 32;
        for (int col = 0; col < 32; col++) {
            int bits = mem[pixelAddr(y, col)] & 0xFF, at = mem[ab + col] & 0xFF;
            int bright = (at & 0x40) >> 3;
            int ink = PALETTE[(at & 7) | bright], paper = PALETTE[((at >> 3) & 7) | bright];
            if ((at & 0x80) != 0 && flash) {
                int tmp = ink;
                ink = paper;
                paper = tmp;
            }
            int q = p + BORDER + col * 8;
            for (int k = 0; k < 8; k++, bits <<= 1) px[q + k] = (bits & 0x80) != 0 ? ink : paper;
        }
    }

    /** Redraws the whole picture from memory (e.g. after loading a state while paused). */
    public void renderStatic() {
        java.util.Arrays.fill(screen, PALETTE[border]);
        for (int y = 0; y < 192; y++) drawPaper(screen, (y + BORDER) * WIDTH, y);
    }

    public int getBorder() {
        return border;
    }

    public long getCycleCount() {
        return cycleCount;
    }

    // ------------------------------------------------------------------ tape helpers

    /** Resets, waits for BASIC and types LOAD "" + ENTER (the tape plays itself). */
    public void autoLoad() {
        hardReset();
        runFast(120); // ~2.4 s: the ROM clears and tests the memory
        // J = LOAD (keyword mode), SYM+P = ", ENTER
        type(new int[][]{{33}, {SYM, 25}, {SYM, 25}, {ENTER}});
    }

    // ------------------------------------------------------------------ snapshots

    private static int u16(byte[] f, int p) {
        return (f[p] & 0xFF) | ((f[p + 1] & 0xFF) << 8);
    }

    public void loadSna(byte[] f) throws IOException {
        if (f.length != 49179) throw new IOException("SNA 48K должен быть 49179 байт");
        hardReset();
        Z80 c = cpu;
        c.i = f[0] & 0xFF;
        int hl_ = u16(f, 1), de_ = u16(f, 3), bc_ = u16(f, 5), af_ = u16(f, 7);
        c.h_ = hl_ >> 8; c.l_ = hl_ & 0xFF; c.d_ = de_ >> 8; c.e_ = de_ & 0xFF;
        c.b_ = bc_ >> 8; c.c_ = bc_ & 0xFF; c.a_ = af_ >> 8; c.f_ = af_ & 0xFF;
        c.setHL(u16(f, 9)); c.setDE(u16(f, 11)); c.setBC(u16(f, 13));
        c.iy = u16(f, 15); c.ix = u16(f, 17);
        c.iff1 = c.iff2 = (f[19] & 4) != 0;
        c.setR(f[20] & 0xFF);
        c.setAF(u16(f, 21));
        c.sp = u16(f, 23);
        c.im = f[25] & 3;
        border = frameStartBorder = f[26] & 7;
        System.arraycopy(f, 27, mem, 0x4000, 0xC000);
        c.pc = rawRead(c.sp) | (rawRead(c.sp + 1) << 8);
        c.sp = (c.sp + 2) & 0xFFFF;
        c.halted = false;
    }

    private static void unpack(byte[] f, int p, int end, byte[] dst, int off, int max) {
        int q = off;
        while (p < end && q < off + max) {
            if (p + 3 < end && (f[p] & 0xFF) == 0xED && (f[p + 1] & 0xFF) == 0xED) {
                int n = f[p + 2] & 0xFF;
                byte v = f[p + 3];
                for (int k = 0; k < n && q < off + max; k++) dst[q++] = v;
                p += 4;
            } else {
                dst[q++] = f[p++];
            }
        }
    }

    public void loadZ80(byte[] f) throws IOException {
        if (f.length < 30) throw new IOException("файл Z80 слишком короткий");
        Z80 c = cpu;
        int pc = u16(f, 6);
        int flags = f[12] & 0xFF;
        if (flags == 0xFF) flags = 1;
        byte[] ram = new byte[0xC000];
        if (pc != 0) {
            int end = f.length;
            if (end >= 4 && f[end - 4] == 0 && (f[end - 3] & 0xFF) == 0xED && (f[end - 2] & 0xFF) == 0xED && f[end - 1] == 0) end -= 4;
            if ((flags & 0x20) != 0) unpack(f, 30, end, ram, 0, 0xC000);
            else System.arraycopy(f, 30, ram, 0, Math.min(0xC000, end - 30));
        } else {
            int extra = u16(f, 30);
            pc = u16(f, 32);
            int hw = f[34] & 0xFF;
            boolean v3 = extra >= 54;
            if (!(hw == 0 || hw == 1 || (v3 && hw == 3)) || (extra >= 6 && (f[37] & 0x80) != 0))
                throw new IOException("это снимок не 48K (тип " + hw + ")");
            int p = 32 + extra;
            boolean any = false;
            while (p + 3 <= f.length) {
                int len = u16(f, p), page = f[p + 2] & 0xFF;
                p += 3;
                int dlen = len == 0xFFFF ? 0x4000 : len;
                int off = page == 8 ? 0 : page == 4 ? 0x4000 : page == 5 ? 0x8000 : -1;
                if (off >= 0) {
                    any = true;
                    if (len == 0xFFFF) System.arraycopy(f, p, ram, off, Math.min(0x4000, f.length - p));
                    else unpack(f, p, Math.min(f.length, p + dlen), ram, off, 0x4000);
                }
                p += dlen;
            }
            if (!any) throw new IOException("в снимке нет страниц памяти");
        }
        hardReset();
        System.arraycopy(ram, 0, mem, 0x4000, 0xC000);
        c.a = f[0] & 0xFF; c.f = f[1] & 0xFF;
        c.setBC(u16(f, 2)); c.setHL(u16(f, 4));
        c.pc = pc;
        c.sp = u16(f, 8);
        c.i = f[10] & 0xFF;
        c.setR((f[11] & 0x7F) | ((flags & 1) << 7));
        border = frameStartBorder = (flags >> 1) & 7;
        c.setDE(u16(f, 13));
        int bc_ = u16(f, 15), de_ = u16(f, 17), hl_ = u16(f, 19);
        c.b_ = bc_ >> 8; c.c_ = bc_ & 0xFF; c.d_ = de_ >> 8; c.e_ = de_ & 0xFF; c.h_ = hl_ >> 8; c.l_ = hl_ & 0xFF;
        c.a_ = f[21] & 0xFF; c.f_ = f[22] & 0xFF;
        c.iy = u16(f, 23); c.ix = u16(f, 25);
        c.iff1 = f[27] != 0; c.iff2 = f[28] != 0;
        c.im = f[29] & 3;
        c.halted = false;
    }

    // ------------------------------------------------------------------ save states

    private static final int STATE_MAGIC = 0x5A583438; // "ZX48"
    private static final int STATE_VERSION = 1;

    public byte[] saveState() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(0xC100);
            DataOutputStream o = new DataOutputStream(bos);
            o.writeInt(STATE_MAGIC);
            o.writeInt(STATE_VERSION);
            Z80 c = cpu;
            int[] regs = {c.a, c.f, c.b, c.c, c.d, c.e, c.h, c.l, c.a_, c.f_, c.b_, c.c_, c.d_, c.e_, c.h_, c.l_,
                c.ix, c.iy, c.sp, c.pc, c.i, c.getR(), c.memptr, c.im};
            for (int v : regs) o.writeShort(v);
            o.writeBoolean(c.iff1);
            o.writeBoolean(c.iff2);
            o.writeBoolean(c.halted);
            o.writeInt((int) c.t);
            o.writeByte(border);
            o.writeBoolean(ear);
            o.write(mem, 0x4000, 0xC000);
            o.writeInt(progStart);
            o.writeInt(progEnd);
            o.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void loadState(byte[] s) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(s));
        if (in.readInt() != STATE_MAGIC || in.readInt() != STATE_VERSION)
            throw new IOException("Неверный файл состояния");
        Z80 c = cpu;
        int[] r = new int[24];
        for (int k = 0; k < r.length; k++) r[k] = in.readUnsignedShort();
        c.a = r[0]; c.f = r[1]; c.b = r[2]; c.c = r[3]; c.d = r[4]; c.e = r[5]; c.h = r[6]; c.l = r[7];
        c.a_ = r[8]; c.f_ = r[9]; c.b_ = r[10]; c.c_ = r[11]; c.d_ = r[12]; c.e_ = r[13]; c.h_ = r[14]; c.l_ = r[15];
        c.ix = r[16]; c.iy = r[17]; c.sp = r[18]; c.pc = r[19]; c.i = r[20]; c.setR(r[21]); c.memptr = r[22]; c.im = r[23];
        c.iff1 = in.readBoolean();
        c.iff2 = in.readBoolean();
        c.halted = in.readBoolean();
        c.t = in.readInt();
        border = frameStartBorder = in.readUnsignedByte();
        ear = in.readBoolean();
        in.readFully(mem, 0x4000, 0xC000);
        progStart = in.readInt();
        progEnd = in.readInt();
        bLogN = 0;
        script = null;
        java.util.Arrays.fill(scriptRows, 0);
        frameOpen = false;
        histCount = 0;
        releaseAllKeys();
    }
}
