package com.alexspec.atari;

/** All calls are made under this class's monitor by the emulation worker. GPL-2.0-or-later. */
final class NativeAtari {
    static { System.loadLibrary("atari_engine"); }
    static Object owner;
    static native boolean open(String systemDirectory,String content,boolean basic);
    static native void close();
    static native double fps();
    static native int frame(int[] pixels,short[] samples,int[] geometry,int buttons,byte[] keys);
    static native void reset();
    static native byte[] save();
    static native boolean load(byte[] data);
    static native byte[] memory();
    private NativeAtari() {}
}
