package com.alexspec.atari.core;

/**
 * The Atari 800XL/65XE (PAL, 64 KB) as the app sees it: a thin layer over the native engine
 * with the same shape as Spectrum48 in the ZX app, so the screens, the memory map and the
 * controls work the same way. All methods except the key ones run on the emulation thread.
 *
 * Keys: codes 0..63 are Atari keyboard keys by their hardware key code (akey.h: A = 0x3F,
 * RETURN = 0x0C ...); then SHIFT, CONTROL, BREAK, the console keys, RESET and joystick 1.
 * GPL-2.0-or-later, see atari/COPYING.
 */
public final class AtariMachine implements MemTrace.Mem {
    public static final int CPU_HZ = 1773447;            // machine cycles per second, PAL
    public static final int T_LINE = 114, LINES = 312, T_FRAME = T_LINE * LINES; // 35568
    public static final int SAMPLE_RATE = 44100;
    public static final int WIDTH = 336, HEIGHT = 240;
    /** first scan line shown in the picture (Screen_atari starts at line 8) */
    public static final int FIRST_LINE = 8;

    public static final int SHIFT = 64, CTRL = 65, BREAK = 66, START = 67, SELECT = 68, OPTION = 69, RESET = 70;
    public static final int JOY_UP = 71, JOY_DOWN = 72, JOY_LEFT = 73, JOY_RIGHT = 74, FIRE = 75;
    public static final int KEY_COUNT = 76;

    /** keys that are often needed by number */
    public static final int RETURN = 0x0C, SPACE = 0x21, ESCAPE = 0x1C, HELP = 0x11, CAPS = 0x3C,
            INVERSE = 0x27, BACKSPACE = 0x34, TAB = 0x2C, MINUS = 0x0E, EQUAL = 0x0F, PLUS = 0x06, ASTERISK = 0x07;

    public final MemTrace trace = new MemTrace();
    /** the picture (WIDTH x HEIGHT ARGB) */
    public final int[] screen = new int[WIDTH * HEIGHT];
    /** registers before the next instruction: A X Y S P PC */
    public final int[] regs = new int[6];

    private final byte[] mem = new byte[65536];
    private final long[] status = new long[14];
    private short[] stereo = new short[16384];

    private long time, instr, lastStart;
    private volatile long frames;
    private int lastPc = -1;
    private boolean midFrame;
    private int beamLine, beamCycle;
    private boolean open, traceOn;

    // keys (held counts, from the UI thread)
    private final int[] held = new int[KEY_COUNT];
    /**
     * Pressed since the current frame began: the machine reads the controls once per frame, so a
     * tap shorter than a frame (or anything at slow speed) would otherwise be lost.
     */
    private final boolean[] latched = new boolean[KEY_COUNT];
    /** frame number when each latch was set; a released key counts until two frames later */
    private final long[] latchFrame = new long[KEY_COUNT];
    /** order in which the normal keys were pressed: the last one still held is the one typed */
    private final int[] pressOrder = new int[64];
    private int pressCounter;

    // ------------------------------------------------------------------ machine

    /**
     * A raw image that is an Atari 2600 cartridge rather than an Atari 8-bit one. 800/XL carts are
     * 4 KB or more and keep 0 in the byte before their start vector (BFFC); 5200 carts start in
     * 4000-BFFF. 2600 games are 2 KB, or 4 KB with reset and interrupt vectors pointing into Fxxx.
     */
    public static boolean looksLike2600(byte[] d) {
        if (d.length == 2048) return true;
        if (d.length != 4096) return false;
        int reset = (d[0xFFC] & 0xFF) | (d[0xFFD] & 0xFF) << 8, irq = (d[0xFFE] & 0xFF) | (d[0xFFF] & 0xFF) << 8;
        return (reset & 0xF000) == 0xF000 && (irq & 0xF000) == 0xF000;
    }


    /** Starts a fresh machine with a program, disk or cartridge ("" = BASIC). */
    public boolean open(String systemDir, String content, boolean basic) {
        open = NativeAtari.open(systemDir, content == null ? "" : content, basic);
        console5200 = open && NativeAtari.is5200();
        NativeAtari.trace(traceOn);
        refresh();
        return open;
    }

    public boolean isOpen() {
        return open;
    }

    private volatile boolean console5200;

    /** running an Atari 5200 console (a 5200 cartridge was opened) */
    public boolean is5200() {
        return console5200;
    }

    public void hardReset() {
        if (!open) return;
        NativeAtari.reset();
        refresh();
    }

    public double fps() {
        return open ? NativeAtari.fps() : CPU_HZ / (double) T_FRAME;
    }

    /** Turns the memory trace on or off (it costs a little time, so it is off without the map). */
    public void setTrace(boolean on) {
        if (on && !traceOn) trace.clear();
        traceOn = on;
        trace.enabled = on;
        if (open) NativeAtari.trace(on);
    }

    public void clearTrace() {
        trace.clear();
        if (open) NativeAtari.traceClear();
    }

    /** Copies the access times from the engine into {@link #trace} (before drawing the map). */
    public void syncTrace() {
        if (open && traceOn) NativeAtari.traceCopy(trace.lastRead, trace.lastWrite, trace.lastExec);
    }

    // ------------------------------------------------------------------ running

    /**
     * Runs about {@code budget} cycles (whole instructions; frames continue across calls) or, with
     * {@code toFrameEnd}, exactly to the end of the current frame. Mono audio of the frames
     * completed meanwhile goes to {@code audio}; returns the number of samples.
     */
    public int run(short[] audio, long budget, boolean toFrameEnd) {
        if (!open) return 0;
        int need = audio == null ? 0 : audio.length * 2;
        if (stereo.length < need) stereo = new short[need];
        applyInput();
        int n = NativeAtari.run(budget, toFrameEnd, audio == null ? null : stereo);
        int mono = 0;
        if (audio != null && n > 0) {
            mono = Math.min(n / 2, audio.length);
            for (int i = 0; i < mono; i++) audio[i] = (short) ((stereo[2 * i] + stereo[2 * i + 1]) / 2);
        }
        refresh();
        return mono;
    }

    public int runFrame(short[] audio) {
        return run(audio, 0, true);
    }

    /** Executes exactly one instruction. */
    public void stepInstruction() {
        if (!open) return;
        applyInput();
        NativeAtari.step(1);
        refresh();
    }

    /** Runs forward {@code t} cycles (whole instructions), no sound. */
    public void forward(long t) {
        if (!open) return;
        applyInput();
        NativeAtari.forward(t);
        refresh();
    }

    /** Goes back {@code n} instructions; false if the history (~10 s) does not reach. */
    public boolean backInstructions(long n) {
        if (!open) return false;
        boolean ok = NativeAtari.backInstructions(n);
        refresh();
        return ok;
    }

    public boolean backTime(long t) {
        if (!open) return false;
        boolean ok = NativeAtari.backTime(t);
        refresh();
        return ok;
    }

    /** Normal play resumes: what happened after this point is not replayed any more. */
    public void forgetFuture() {
        if (open) NativeAtari.forgetFuture();
    }

    public float historySeconds() {
        return open ? (float) NativeAtari.historySeconds() : 0;
    }

    /** Re-reads the picture, memory and registers from the engine. */
    public void refresh() {
        if (!open) return;
        NativeAtari.picture(screen);
        NativeAtari.memory(mem);
        NativeAtari.status(status);
        time = status[0];
        instr = status[1];
        frames = status[2];
        lastStart = status[3];
        lastPc = (int) status[4];
        midFrame = status[5] != 0;
        beamLine = (int) status[6];
        beamCycle = (int) status[7];
        for (int i = 0; i < 6; i++) regs[i] = (int) status[8 + i];
    }

    /** absolute emulated time in machine cycles since the program was opened */
    public long time() {
        return time;
    }

    public long instructions() {
        return instr;
    }

    public long frames() {
        return frames;
    }

    /** start time of the last executed instruction */
    public long lastInstrStart() {
        return lastStart;
    }

    /** address of the last executed instruction, -1 if none */
    public int lastInstrPc() {
        return lastPc;
    }

    public int pc() {
        return regs[5];
    }

    public int sp() {
        return 0x100 | regs[3];
    }

    /** scan line and cycle of the beam, {line, cycle}; null at a frame boundary */
    public int[] beamRaw() {
        return midFrame ? new int[]{beamLine, beamCycle} : null;
    }

    /**
     * Where the beam is in the picture, {x, y}; null at a frame boundary or outside the picture.
     * One machine cycle is 2 colour clocks = 4 picture pixels; the picture starts at cycle 22.
     */
    public int[] beam() {
        if (!midFrame) return null;
        int row = beamLine - FIRST_LINE;
        if (row < 0 || row >= HEIGHT) return null;
        int x = Math.max(0, Math.min(WIDTH - 1, beamCycle * 4 - 88));
        return new int[]{x, row};
    }

    @Override
    public int rawRead(int addr) {
        return mem[addr & 0xFFFF] & 0xFF;
    }

    public int word(int addr) {
        return rawRead(addr) | (rawRead(addr + 1) << 8);
    }

    // ------------------------------------------------------------------ state

    public byte[] saveState() {
        if (!open) return null;
        byte[] s = NativeAtari.save();
        refresh();
        return s;
    }

    public boolean loadState(byte[] s) {
        if (!open || s == null) return false;
        boolean ok = NativeAtari.load(s);
        refresh();
        return ok;
    }

    // ------------------------------------------------------------------ keys (UI thread)

    public synchronized void keyDown(int code) {
        if (code < 0 || code >= KEY_COUNT) return;
        held[code]++;
        latched[code] = true;
        latchFrame[code] = frames;
        if (code < 64) pressOrder[code] = ++pressCounter;
        pushInput();
    }

    public synchronized void keyUp(int code) {
        if (code < 0 || code >= KEY_COUNT || held[code] == 0) return;
        held[code]--;
        pushInput();
    }

    public synchronized void releaseAllKeys() {
        java.util.Arrays.fill(held, 0);
        java.util.Arrays.fill(latched, false);
        pushInput();
    }

    public synchronized boolean isKeyDown(int code) {
        return code >= 0 && code < KEY_COUNT && held[code] > 0;
    }

    /** What the machine gets from the next frame on. */
    private synchronized void pushInput() {
        int key = -1, best = 0;
        for (int k = 0; k < 64; k++) {
            if (down(k) && pressOrder[k] > best) {
                best = pressOrder[k];
                key = k;
            }
        }
        boolean shift = down(SHIFT), ctrl = down(CTRL);
        int akey = key;
        if (akey >= 0) {
            if (shift) akey |= 0x40;
            if (ctrl) akey |= 0x80;
        }
        if (down(BREAK)) akey = -5;
        if (down(RESET)) akey = -2;
        int consol = 7;
        if (down(START)) consol &= ~1;
        if (down(SELECT)) consol &= ~2;
        if (down(OPTION)) consol &= ~4;
        int buttons = 0;
        if (down(FIRE)) buttons |= 1;
        if (down(JOY_UP)) buttons |= 1 << 4;
        if (down(JOY_DOWN)) buttons |= 1 << 5;
        if (down(JOY_LEFT)) buttons |= 1 << 6;
        if (down(JOY_RIGHT)) buttons |= 1 << 7;
        inAkey = akey;
        inShift = shift;
        inConsol = consol;
        inButtons = buttons;
    }

    private boolean down(int code) {
        return held[code] > 0 || latched[code];
    }

    private volatile int inAkey = -1, inConsol = 7, inButtons;
    private volatile boolean inShift;

    /**
     * Hands the current controls to the engine (emulation thread, before running). Once a frame
     * has started with them, released keys stop counting as pressed.
     */
    private void applyInput() {
        synchronized (this) {
            boolean changed = false;
            for (int k = 0; k < KEY_COUNT; k++) {
                // by then at least one whole frame has started with the key
                if (latched[k] && held[k] == 0 && frames >= latchFrame[k] + 2) {
                    latched[k] = false;
                    changed = true;
                }
            }
            if (changed) pushInput();
        }
        NativeAtari.setInput(inAkey, inShift, inConsol, inButtons);
    }
}
