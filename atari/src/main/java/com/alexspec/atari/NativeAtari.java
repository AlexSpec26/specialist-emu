package com.alexspec.atari;

/**
 * Java side of the native Atari800 engine (src/main/jni/native/jni.c).
 *
 * The engine is a single global machine, so every call must be made by the emulation
 * thread while holding this class's monitor (see {@link AtariRunner}).
 * GPL-2.0-or-later, see atari/COPYING.
 */
final class NativeAtari {
    static { System.loadLibrary("atari_engine"); }

    /** Largest picture the engine can produce: 400×300 pixels. */
    static final int MAX_PIXELS = 400 * 300;
    /** Size of the key-state array passed to {@link #frame}: libretro RETROK_* codes. */
    static final int KEYS = 512;

    /** The runner that currently owns the engine; guarded by NativeAtari.class. */
    static Object owner;

    /**
     * Starts a fresh machine.
     * @param systemDirectory folder with optional ATARIXL.ROM / ATARIBAS.ROM
     * @param content program, disk or cartridge file; "" boots straight into BASIC
     * @param basic whether the built-in BASIC cartridge is enabled
     */
    static native boolean open(String systemDirectory, String content, boolean basic);

    static native void close();

    /** Frames per second of the current machine (PAL ≈ 49.86). */
    static native double fps();

    /**
     * Runs one video frame.
     * @param pixels ARGB output, at least {@link #MAX_PIXELS} long
     * @param samples interleaved stereo 16-bit output at 44100 Hz
     * @param geometry receives width and height of the picture
     * @param buttons libretro joypad bits (see {@link PlayView})
     * @param keys {@link #KEYS} bytes, 1 = key held
     * @return number of audio samples written, or -1 on error
     */
    static native int frame(int[] pixels, short[] samples, int[] geometry, int buttons, byte[] keys);

    /** Cold start: reloads the current program, or reboots BASIC. */
    static native void reset();

    static native byte[] save();

    static native boolean load(byte[] data);

    /**
     * Copy of the engine's 64 KB memory array (RAM plus mapped ROM; chip registers
     * D000–D7FF are not included), or null if nothing is running.
     */
    static native byte[] memory();

    private NativeAtari() {}
}
