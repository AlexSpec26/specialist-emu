package com.alexspec.atari.core;

/**
 * Desktop test of the JNI bridge (src/main/jni/native/jni.c), the same code as in the APK:
 * opening, running, stepping back and forth, the trace, save/load.
 *   JAVA_HOME=... make -s -f atari/src/main/jni/Makefile.host jni-test DEMO=atari/src/main/assets/demos/catch-the-star.xex
 */
public final class AtariJniTest {
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }

    public static void main(String[] args) {
        check(NativeAtari.open("/tmp/atari-native-system", args[0], false), "open");
        short[] audio = new short[16384];
        NativeAtari.setInput(-1, false, 7, 0);
        for (int i = 0; i < 400; i++) check(NativeAtari.run(0, true, audio) > 0, "frame + audio");
        int[] picture = new int[336 * 240];
        NativeAtari.picture(picture);
        byte[] ram = new byte[65536];
        check(NativeAtari.memory(ram) && ram[0x81] == 4, "memory");
        NativeAtari.setInput(-1, false, 7, 1 << 7);
        for (int i = 0; i < 16; i++) NativeAtari.run(0, true, audio);
        NativeAtari.memory(ram);
        check((ram[0x81] & 255) > 4, "joystick");

        long[] st = new long[14];
        NativeAtari.trace(true);
        NativeAtari.run(5000, false, audio);
        NativeAtari.status(st);
        check(st[5] == 1, "stopped inside a frame");
        long t = st[0], pc = st[13];
        NativeAtari.step(1);
        NativeAtari.status(st);
        check(st[4] == pc && st[0] > t, "one instruction");
        long[] r = new long[65536], w = new long[65536], x = new long[65536];
        NativeAtari.traceCopy(r, w, x);
        check(x[(int) pc] == st[3] + 1, "trace marks the executed instruction");
        check(NativeAtari.backInstructions(1), "one instruction back");
        NativeAtari.status(st);
        check(st[0] == t && st[13] == pc, "back at the same place");

        byte[] state = NativeAtari.save();
        check(state != null && state.length > 10000, "save");
        check(NativeAtari.load(state), "load");
        NativeAtari.reset();
        NativeAtari.close();
        System.out.println("ANDROID JNI BRIDGE OK (host JVM)");
    }
}
