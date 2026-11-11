package com.alexspec.dos;

/**
 * The DOS machine in native code (DOSBox Pure core + src/main/jni/native/engine.c). One machine
 * per process, started once; all calls from the emulation thread.
 */
final class NativeDos {
    static {
        System.loadLibrary("dos_engine");
    }

    private NativeDos() {
    }

    static native boolean open(String systemDir, String saveDir, String content);

    static native boolean isOpen();

    static native void option(String key, String value);

    /** one video frame; returns the number of stereo samples (L, R, L, R…) put into audio */
    static native int frame(short[] audio);

    static native double fps();

    static native int sampleRate();

    static native int width();

    static native int height();

    static native double aspect();

    /** copies the last picture (width*height ARGB); returns the number of pixels or 0 */
    static native int picture(int[] argb);

    static native void key(int retrok, boolean down);

    static native void releaseKeys();

    /** touch pad mouse: x, y in -32767..32767 over the picture */
    static native void pointer(int x, int y, boolean pressed, int count);

    static native byte[] save();

    static native boolean load(byte[] state);

    /** back at least {@code seconds}; returns how far it went, 0 if there is no history */
    static native double rewind(double seconds);

    static native double history();
}
