import com.alexspec.specialist.core.SpecKeys;
import com.alexspec.specialist.core.Specialist;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Time control of the Specialist core (SPEC-020): slow motion, single steps, going back and the
 * key log. The Monitor-2 ROM is started and keys are typed into it (the monitor echoes them into
 * video memory), so the machine depends on the keys and their timing.
 *
 * Usage: javac -d /tmp/sp app/src/main/java/com/alexspec/specialist/core/*.java tools/SpecTimeTest.java
 *        java -cp /tmp/sp SpecTimeTest shell/base/spec_m2.rom
 */
public final class SpecTimeTest {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static Specialist boot(byte[] rom) {
        Specialist m = new Specialist();
        m.loadRom(rom);
        m.hardReset();
        short[] audio = new short[4096];
        for (int i = 0; i < 50; i++) m.runFrame(audio);
        tap(m, SpecKeys.byLabel("F1"), 10);
        tap(m, SpecKeys.ENTER, 10);
        for (int i = 0; i < 50; i++) m.runFrame(audio);
        return m;
    }

    /** holds a key for {@code frames} frames, then releases it for as many */
    private static void tap(Specialist m, int key, int frames) {
        short[] audio = new short[4096];
        m.keyDown(key);
        for (int i = 0; i < frames; i++) m.runFrame(audio);
        m.keyUp(key);
        for (int i = 0; i < frames; i++) m.runFrame(audio);
    }

    private static long hash(Specialist m) {
        long h = 1469598103934665603L;
        for (int a = 0; a < 0xC000; a++) h = (h ^ m.rawRead(a)) * 1099511628211L;
        h = (h ^ m.cpu.pc) * 1099511628211L;
        h = (h ^ m.cpu.sp) * 1099511628211L;
        return (h ^ m.cpu.a) * 1099511628211L;
    }

    /** the keys pressed in frame f of the script: letters typed with pauses between them */
    private static int scriptKey(int f) {
        String[] letters = {"D", "F", "0", "1", "2", "J", "H", "Q"};
        return f % 8 < 3 ? SpecKeys.byLabel(letters[(f / 8) % letters.length]) : SpecKeys.NONE;
    }

    public static void main(String[] args) throws Exception {
        byte[] rom = Files.readAllBytes(Paths.get(args[0]));
        short[] audio = new short[4096];
        int frames = 300;

        // reference: whole frames with the scripted keys (each frame runs to a fixed end time)
        Specialist ref = boot(rom);
        long[] refHash = new long[frames];
        int held = SpecKeys.NONE;
        long r0 = ref.time();
        for (int f = 0; f < frames; f++) {
            int k = scriptKey(f);
            if (k != held) {
                if (held != SpecKeys.NONE) ref.keyUp(held);
                if (k != SpecKeys.NONE) ref.keyDown(k);
                held = k;
            }
            ref.run(audio, (int) (r0 + (long) (f + 1) * Specialist.CYCLES_PER_FRAME - ref.time()));
            refHash[f] = hash(ref);
        }
        check(refHash[frames - 1] != refHash[0], "the typing changes the machine");

        // 1. the same frames run in random slices = the same machine
        Specialist m = boot(rom);
        java.util.Random rnd = new java.util.Random(7);
        held = SpecKeys.NONE;
        long t0 = m.time();
        for (int f = 0; f < frames; f++) {
            int k = scriptKey(f);
            if (k != held) {
                if (held != SpecKeys.NONE) m.keyUp(held);
                if (k != SpecKeys.NONE) m.keyDown(k);
                held = k;
            }
            long end = t0 + (long) (f + 1) * Specialist.CYCLES_PER_FRAME;
            while (m.time() < end) m.run(audio, (int) Math.min(1 + rnd.nextInt(3000), end - m.time()));
            check(hash(m) == refHash[f], "slices = whole frames, frame " + f);
        }
        System.out.println("SLOW MOTION = WHOLE FRAMES OK");

        // 2. one instruction forward and back
        long i0 = m.instr, c0 = m.time(), h0 = hash(m);
        int pc0 = m.cpu.pc;
        m.stepInstruction();
        check(m.instr == i0 + 1 && m.lastInstrPc == pc0, "one step");
        long h1 = hash(m);
        int pc1 = m.cpu.pc;
        check(m.backInstructions(1), "one back");
        check(m.instr == i0 && m.time() == c0 && hash(m) == h0, "back at the same place");
        m.stepInstruction();
        check(hash(m) == h1 && m.cpu.pc == pc1, "the same step again");
        System.out.println("STEP FORWARD/BACK OK");

        // 3. a second back with typing in it, then forward: the same keys are replayed
        long tEnd = m.time(), hEnd = hash(m);
        check(m.backTime(Specialist.CPU_HZ), "a second back");
        check(m.time() >= tEnd - Specialist.CPU_HZ && m.time() < tEnd - Specialist.CPU_HZ + 20, "back to the right time");
        m.forward(tEnd - m.time());
        check(m.time() == tEnd && hash(m) == hEnd, "forward replays the keys");
        System.out.println("SECOND BACK/FORWARD WITH KEY REPLAY OK");

        // 4. after forgetting the future, the live keys count (here: none, so typing stops)
        check(m.backTime(Specialist.CPU_HZ), "back again");
        m.forgetFuture();
        m.releaseAllKeys();
        m.forward(tEnd - m.time());
        check(hash(m) != hEnd, "live keys after forgetting the future");
        System.out.println("FORGET FUTURE OK");

        // 5. history: at least a minute
        Specialist h = boot(rom);
        for (int f = 0; f < 50 * 75; f++) h.runFrame(audio);
        float secs = h.historySeconds();
        check(secs >= 60f && secs <= 63f, "history at least a minute, got " + secs);
        long before = h.time();
        check(h.backTime(55L * Specialist.CPU_HZ), "55 s back");
        check(Math.abs(before - h.time() - 55L * Specialist.CPU_HZ) < 20, "55 s exactly");
        check(!h.backTime(10L * Specialist.CPU_HZ), "not beyond the history");
        System.out.printf("HISTORY %.1f s OK%n", secs);

        // 6. memory trace: the last instruction is stamped as executed
        Specialist tr = boot(rom);
        tr.trace.enabled = true;
        tr.trace.clear();
        int pc = tr.cpu.pc;
        tr.stepInstruction();
        check(tr.trace.lastExec[pc] == tr.lastInstrStart + 1, "trace stamp of the executed instruction");
        System.out.println("TRACE OK");

        // 7. saves: version 2 (build 24 and from build 29) restores exactly; version 1 (builds
        // 25..28: the same without the 60-byte tail) is still read
        Specialist sv = boot(rom);
        for (int f = 0; f < 40; f++) sv.runFrame(audio);
        byte[] st = sv.saveState();
        check(st[7] == 2, "saves are version 2");
        for (int f = 0; f < 50; f++) sv.runFrame(audio);
        long after = hash(sv);
        Specialist ld = boot(rom);
        for (int f = 0; f < 77; f++) ld.runFrame(audio);
        ld.loadState(st);
        for (int f = 0; f < 50; f++) ld.runFrame(audio);
        check(hash(ld) == after, "a version 2 save continues exactly");
        byte[] v1 = Arrays.copyOf(st, st.length - 60);
        v1[7] = 1;
        ld.loadState(st);
        long atSave = hash(ld);
        for (int f = 0; f < 9; f++) ld.runFrame(audio);
        ld.loadState(v1);
        check(hash(ld) == atSave, "a version 1 save is read");
        boolean refused = false;
        try {
            ld.loadState(Arrays.copyOf(st, st.length - 61));
        } catch (java.io.IOException e) {
            refused = true;
        }
        check(refused, "a truncated save is refused");
        System.out.println("SAVE FORMATS OK");
        System.out.println("SPEC TIME CONTROL OK");
    }
}
