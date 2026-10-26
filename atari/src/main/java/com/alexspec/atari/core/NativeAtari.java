package com.alexspec.atari.core;

/**
 * Calls into the native Atari engine (src/main/jni/native: engine.h, jni.c). The engine is a
 * single global machine; all calls come from the emulation thread through {@link AtariMachine}.
 * GPL-2.0-or-later, see atari/COPYING.
 */
public final class NativeAtari {
    static { System.loadLibrary("atari_engine"); }

    /** Starts a fresh machine; content "" = BASIC. */
    public static native boolean open(String systemDirectory, String content, boolean basic);

    public static native void close();

    /** the content started an Atari 5200 console instead of the 800XL */
    public static native boolean is5200();

    /** Cold start of the current program. */
    public static native void reset();

    /** Controls for the next new frame: Atari key code (-1 none), SHIFT, console bits, joypad bits. */
    public static native void setInput(int akey, boolean shift, int consol, int buttons);

    /** Runs {@code cycles} forward (or to the end of the frame); returns stereo samples written. */
    public static native int run(long cycles, boolean toFrameEnd, short[] audio);

    public static native void step(long instructions);

    public static native void forward(long cycles);

    public static native boolean backInstructions(long n);

    public static native boolean backTime(long cycles);

    public static native void forgetFuture();

    public static native double historySeconds();

    public static native double fps();

    /**
     * time, instructions, frames, last instruction start, last pc, inside a frame (0/1),
     * beam line, beam cycle, A, X, Y, S, P, PC.
     */
    public static native void status(long[] out);

    public static native void picture(int[] argb);

    public static native boolean memory(byte[] out);

    public static native void trace(boolean on);

    public static native void traceClear();

    public static native void traceCopy(long[] reads, long[] writes, long[] execs);

    public static native byte[] save();

    public static native boolean load(byte[] state);

    private NativeAtari() {
    }
}
