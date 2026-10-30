import com.alexspec.zx.core.Spectrum48;
import com.alexspec.zx.core.Tape;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Going back in time in the ZX core: at least a minute of history, exact replay of the logged
 * keys (snapshots are every 5 frames, keys are logged every frame), single steps.
 *
 * Usage: javac -d /tmp/zx zx/src/main/java/com/alexspec/zx/core/*.java tools/ZxTimeTest.java
 *        java -cp /tmp/zx ZxTimeTest zx/src/main/assets/48k.rom "zx/src/main/assets/games/Sir Ababol.tzx"
 */
public final class ZxTimeTest {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static long hash(Spectrum48 m) {
        long h = 1469598103934665603L;
        for (int a = 0x4000; a < 0x10000; a++) h = (h ^ m.rawRead(a)) * 1099511628211L;
        h = (h ^ m.cpu.pc) * 1099511628211L;
        return (h ^ m.cpu.sp) * 1099511628211L;
    }

    /** Kempston in frame f: walk right, jump, walk left, pause */
    private static int stick(int f) {
        int k = Spectrum48.KEMPSTON;
        int p = f % 60;
        return p < 20 ? k : p < 26 ? k + 4 : p < 40 ? k + 1 : -1;
    }

    public static void main(String[] args) throws Exception {
        Spectrum48 m = new Spectrum48(Files.readAllBytes(Paths.get(args[0])));
        Tape t = Tape.fromTzx(Files.readAllBytes(Paths.get(args[1])));
        t.rewind();
        m.tape = t;
        m.autoLoad();
        short[] audio = new short[4096];
        for (int i = 0; i < 600; i++) m.runFrame(audio);
        m.clearHistory();

        // play 70 s with the stick, remembering the machine at every frame end
        int frames = 70 * 50;
        long[] at = new long[frames], h = new long[frames];
        int held = -1;
        for (int f = 0; f < frames; f++) {
            int s = stick(f);
            if (s != held) {
                if (held >= 0) m.keyUp(held);
                if (s >= 0) m.keyDown(s);
                held = s;
            }
            m.runFrame(audio);
            at[f] = m.time();
            h[f] = hash(m);
        }
        if (held >= 0) m.keyUp(held);
        check(h[frames - 1] != h[frames - 600], "the stick changes the game");

        float secs = m.historySeconds();
        check(secs >= 60f && secs <= 63f, "history at least a minute, got " + secs);

        // back to points 1, 7 and 55 s ago: the replay with the logged keys gives the same machine
        for (int back : new int[]{50, 7 * 50 + 3, 55 * 50 + 1}) {
            int f = frames - 1 - back;
            check(m.backTime(m.time() - at[f]), "back " + back + " frames");
            check(m.time() == at[f] && hash(m) == h[f], "same machine " + back + " frames back");
        }
        check(!m.backTime(20L * Spectrum48.CPU_HZ), "not beyond the history");
        System.out.printf("HISTORY %.1f s, KEY REPLAY OK%n", secs);

        // one instruction forward and back
        long i0 = m.instr, t0 = m.time(), h0 = hash(m);
        m.stepInstruction();
        long h1 = hash(m);
        check(m.backInstructions(1) && m.instr == i0 && m.time() == t0 && hash(m) == h0, "one back");
        m.stepInstruction();
        check(hash(m) == h1, "the same step again");
        System.out.println("STEP FORWARD/BACK OK");
        System.out.println("ZX TIME CONTROL OK");
    }
}
