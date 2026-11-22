package com.alexspec.atari.core;

/**
 * Desktop test of the JNI bridge (src/main/jni/native/jni.c), the same code as in the APK:
 * opening, running, stepping back and forth, the trace, save/load, ⟲ 5 s of the player's clock.
 *   JAVA_HOME=... make -s -f atari/src/main/jni/Makefile.host jni-test DEMO=atari/src/main/assets/demos/catch-the-star.xex
 */
public final class AtariJniTest {
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }

    /** letters A on the text screen (screen code 21) */
    private static int countA(AtariMachine m) {
        int screen = m.word(0x58), n = 0;
        for (int i = 0; i < 960; i++) if ((m.rawRead(screen + i) & 0x7F) == 0x21) n++;
        return n;
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

        // the app's machine layer: a key tapped between two frames still reaches the machine
        AtariMachine m = new AtariMachine();
        check(m.open("/tmp/atari-native-system", "", true), "AtariMachine open BASIC");
        for (int i = 0; i < 220; i++) m.runFrame(audio);
        int before = countA(m);
        m.keyDown(0x3F); // A
        m.keyUp(0x3F);
        for (int i = 0; i < 10; i++) m.runFrame(audio);
        int typed = countA(m) - before;
        check(typed == 1, "tapped key typed exactly once (" + typed + ")");
        m.setTrace(true);
        m.run(audio, 3000, false);
        long t2 = m.time();
        m.stepInstruction();
        check(m.time() > t2 && m.backInstructions(1) && m.time() == t2, "AtariMachine steps");
        m.syncTrace();
        check(m.trace.lastExec[m.pc()] > 0 || m.trace.lastExec[m.lastInstrPc()] > 0, "AtariMachine trace");

        // ⟲ 5 s (ATARI-008): at x4 the player's 5 s are 20 s of the machine; a save keeps them
        check(m.open("/tmp/atari-native-system", args[0], false), "AtariMachine open demo");
        m.setPlayerRate(1.0 / (4.0 * AtariMachine.CPU_HZ));
        for (int i = 0; i < 1200; i++) m.runFrame(audio); // 24 s of the machine = 6 s of the player
        long tx4 = m.time();
        double went = m.backPlayer(5);
        check(Math.abs(went - 5) < 1e-3, "back 5 s at x4 (" + went + ")");
        long gone = tx4 - m.time();
        check(Math.abs(gone - 20L * AtariMachine.CPU_HZ) < 200, "x4: 20 s of the machine (" + gone + ")");
        // ⟲ while playing, then forgetFuture (as EmuActivity does): the live joystick moves the
        // player at once, not the recorded one (ATARI-BUG-002)
        {
            m.forgetFuture();
            int x0 = m.rawRead(0x81);
            m.keyDown(AtariMachine.JOY_RIGHT);
            for (int i = 0; i < 100; i++) m.runFrame(audio);
            m.keyUp(AtariMachine.JOY_RIGHT);
            int right = m.rawRead(0x81);
            m.backPlayer(100.0 * AtariMachine.T_FRAME / (4.0 * AtariMachine.CPU_HZ)); // the same 100 frames back (x4 clock)
            check(m.rawRead(0x81) == x0, "back to before the move");
            m.forgetFuture();
            for (int i = 0; i < 100; i++) m.runFrame(audio); // nothing held now
            // (the frame it landed in keeps its recorded controls: at most one step)
            check(right > x0 + 3 && m.rawRead(0x81) <= x0 + 1, "live controls after ⟲ (" + x0 + " " + right + " " + m.rawRead(0x81) + ")");
        }
        m.forgetFuture();
        m.setPlayerRate(1.0 / AtariMachine.CPU_HZ);
        for (int i = 0; i < 400; i++) m.runFrame(audio);
        byte[] saved = m.saveState();
        check(saved != null && saved.length > 1000, "save with history");
        long at = m.time();
        check(m.open("/tmp/atari-native-system", args[0], false) && m.loadState(saved), "load with history");
        check(m.time() == at && m.historyPlayerSeconds() > 8, "history after load (" + m.historyPlayerSeconds() + ")");
        check(Math.abs(m.backPlayer(5) - 5) < 1e-3, "back 5 s after load");
        byte[] cut = java.util.Arrays.copyOf(saved, saved.length / 2);
        check(!m.loadState(cut), "a cut save is refused");
        NativeAtari.close();

        // Atari 2600 images are recognised before opening; Atari 8-bit and 5200 ones are not flagged
        byte[] vcs = new byte[4096];
        vcs[0xFFC] = 0x00; vcs[0xFFD] = (byte) 0xF0; vcs[0xFFE] = 0x00; vcs[0xFFF] = (byte) 0xF0;
        byte[] a800 = new byte[4096];
        a800[0xFFA] = 0x00; a800[0xFFB] = (byte) 0xB0; a800[0xFFD] = 0x04; a800[0xFFE] = 0x00; a800[0xFFF] = (byte) 0xB0;
        check(AtariMachine.looksLike2600(vcs) && AtariMachine.looksLike2600(new byte[2048]), "2600 recognised");
        check(!AtariMachine.looksLike2600(a800) && !AtariMachine.looksLike2600(new byte[8192]), "8-bit not flagged");
        System.out.println("ANDROID JNI BRIDGE + MACHINE LAYER OK (host JVM)");
    }
}
