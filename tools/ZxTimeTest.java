import com.alexspec.zx.core.Spectrum48;
import com.alexspec.zx.core.Tape;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Going back in time in the ZX core: at least a minute of history, exact replay of the logged
 * keys (snapshots are every 5 frames, keys are logged every frame), single steps; going back 5 s
 * of the player's clock at real speed, ×4 and slow motion (ZX-016); save files with the history
 * (version 2), old files (version 1), broken files rejected.
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

    private static final double REAL = 1.0 / (50.0 * Spectrum48.T_FRAME);

    /**
     * ⟲ 5 s goes back 5 s of the time the player lived through: at real speed 250 frames, at ×4
     * 1000 frames, at 1/8 about 31 frames — exactly to that moment, the same machine as then.
     */
    private static void playerClock(Spectrum48 m, short[] audio) {
        double[] rates = {REAL, REAL / 4, REAL * 8};
        String[] names = {"x1", "x4", "1/8"};
        for (int r = 0; r < rates.length; r++) {
            m.playerRate = rates[r];
            // 17 s of the player's clock at this speed, with the stick (3 x 5 s back fit)
            int frames = (int) Math.round(17.0 / (rates[r] * Spectrum48.T_FRAME));
            long[] at = new long[frames], h = new long[frames];
            double[] pl = new double[frames];
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
                pl[f] = m.playerTime();
            }
            if (held >= 0) m.keyUp(held);
            // a frame end about 5 s of the player's clock back
            int f = frames - 1 - (int) Math.round(5.0 / (rates[r] * Spectrum48.T_FRAME));
            double want = m.playerTime() - pl[f];
            double went = m.backPlayer(want);
            check(Math.abs(went - want) < 1e-6, names[r] + ": went back " + went + " s, wanted " + want);
            check(m.time() == at[f] && hash(m) == h[f], names[r] + ": the same machine as 5 s ago");
            // and a plain 5 s twice more: each time 5.000 s of the player's clock
            for (int k = 0; k < 2; k++) {
                double p0 = m.playerTime();
                double g = m.backPlayer(5);
                check(Math.abs(g - 5) < 0.001 && Math.abs(p0 - m.playerTime() - 5) < 0.001, names[r] + ": 5 s, got " + g);
            }
            System.out.printf("PLAYER CLOCK %s: 3 x 5 s back = %.3f s of the game OK%n", names[r],
                    (at[frames - 1] - m.time()) / (double) Spectrum48.CPU_HZ);
        }
        m.playerRate = REAL;
        for (int f = 0; f < 50 * 70; f++) m.runFrame(audio);
        double h = m.historyPlayerSeconds();
        check(h >= 60 && h <= 63, "a minute of the player's clock, got " + h);
        double g = m.backPlayer(1000);
        check(g > 60 && g < 63, "not beyond the history, went " + g);
    }

    /** a save file keeps the history: after loading, going back gives the same machine */
    private static void saveFiles(Spectrum48 m, String rom, short[] audio) throws Exception {
        for (int f = 0; f < 50 * 65; f++) {
            if (f % 60 == 0) m.keyDown(Spectrum48.KEMPSTON);
            if (f % 60 == 20) m.keyUp(Spectrum48.KEMPSTON);
            m.runFrame(audio);
        }
        m.tape = null;
        byte[] st = m.saveState();
        Spectrum48 m2 = new Spectrum48(Files.readAllBytes(Paths.get(rom)));
        m2.loadState(st);
        check(m2.time() == m.time() && hash(m2) == hash(m) && m2.playerTime() == m.playerTime(), "loaded = saved");
        check(m2.historyPlayerSeconds() > 59, "history in the file: " + m2.historyPlayerSeconds() + " s");
        for (double back : new double[]{5, 5, 23.3}) {
            double a = m.backPlayer(back), b = m2.backPlayer(back);
            check(Math.abs(a - b) < 1e-6 && m.time() == m2.time() && hash(m) == hash(m2), "same machine after " + back + " s back");
        }
        // after going back the loaded machine plays on exactly like the original
        for (int f = 0; f < 100; f++) {
            m.runFrame(audio);
            m2.runFrame(audio);
        }
        check(hash(m) == hash(m2), "plays on the same");
        System.out.printf("SAVE WITH HISTORY: %d KB, %.1f s back after loading OK%n", st.length / 1024,
                m2.historyPlayerSeconds());

        // a broken file is refused and leaves the machine as it was
        long h0 = hash(m2), t0 = m2.time();
        boolean refused = false;
        try {
            m2.loadState(java.util.Arrays.copyOf(st, st.length / 2));
        } catch (java.io.IOException e) {
            refused = true;
        }
        check(refused && hash(m2) == h0 && m2.time() == t0, "broken file refused, machine untouched");

        // version 1 (the machine only, as before) still loads
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream o = new java.io.DataOutputStream(bos);
        o.writeInt(0x5A583438);
        o.writeInt(1);
        com.alexspec.zx.core.Z80 c = m.cpu;
        for (int v : new int[]{c.a, c.f, c.b, c.c, c.d, c.e, c.h, c.l, c.a_, c.f_, c.b_, c.c_, c.d_, c.e_, c.h_, c.l_,
                c.ix, c.iy, c.sp, c.pc, c.i, c.getR(), c.memptr, c.im}) o.writeShort(v);
        o.writeBoolean(c.iff1);
        o.writeBoolean(c.iff2);
        o.writeBoolean(c.halted);
        o.writeInt((int) c.t);
        o.writeByte(7);
        o.writeBoolean(false);
        for (int a = 0x4000; a < 0x10000; a++) o.writeByte(m.rawRead(a));
        o.writeInt(-1);
        o.writeInt(-1);
        Spectrum48 m3 = new Spectrum48(Files.readAllBytes(Paths.get(rom)));
        m3.loadState(bos.toByteArray());
        check(hash(m3) == hash(m), "version 1 file loads");
        System.out.println("OLD AND BROKEN FILES OK");
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

        playerClock(m, audio);
        saveFiles(m, args[0], audio);
        System.out.println("ZX TIME CONTROL OK");
    }
}
