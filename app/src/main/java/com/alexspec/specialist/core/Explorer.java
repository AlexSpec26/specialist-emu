package com.alexspec.specialist.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The explorer bot (SPEC-022): plays a game by trying many short futures on copies of the machine
 * and playing the best one a little, then planning again. A future is worth more when the hero gets
 * to places he has not been, when the score goes up, and when he moves away from where he has been
 * hanging about; a lost life outweighs everything. The futures it tried are kept for drawing.
 *
 * Everything game-specific is in a {@link Profile} (where the hero is, lives, score, keys). Pure
 * Java: the app drives it from the emulation thread, the desktop tools (tools/bot) from a loop.
 */
public final class Explorer {

    // ------------------------------------------------------------------ the game profile

    /** What the bot has to know about one game; read from a small text file (tools/bot/prof, assets/bot). */
    public static final class Profile {
        public String title = "";
        /** CRC32 of the .rks files this profile is for, and lower-case file names (without .rks) */
        public final Set<Long> crcs = new HashSet<>();
        public final Set<String> names = new HashSet<>();
        String heroKind = "xy";
        int ha, hb, ox, oy;
        String livesKind = "byte", scoreKind = "ascii";
        int[] livesArg = {0}, scoreArg = {0, 1};
        int step = 8, tail = -1, tick = -1;
        int[][] actions;

        public static Profile parse(String text) {
            Profile p = new Profile();
            Map<String, String> kv = new HashMap<>();
            for (String line : text.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int e = line.indexOf('=');
                if (e > 0) kv.put(line.substring(0, e).trim(), line.substring(e + 1).trim());
            }
            p.title = kv.getOrDefault("title", kv.getOrDefault("game", "?").replace(".rks", ""));
            for (String c : kv.getOrDefault("crc", "").split("[ ,]+")) if (!c.isEmpty()) p.crcs.add(Long.parseLong(c, 16));
            for (String n : kv.getOrDefault("names", "").split(",")) if (!n.trim().isEmpty()) p.names.add(n.trim().toLowerCase(java.util.Locale.ROOT));
            String[] h = kv.get("hero").split(" ");
            p.heroKind = h[0];
            p.ha = hex(h[1]);
            p.hb = hex(h[2]);
            if (h.length > 4) {
                p.ox = Integer.parseInt(h[3]);
                p.oy = Integer.parseInt(h[4]);
            }
            String[] l = kv.get("lives").split(" ");
            p.livesKind = l[0];
            p.livesArg = args(l);
            String[] s = kv.get("score").split(" ");
            p.scoreKind = s[0];
            p.scoreArg = args(s);
            p.step = Integer.parseInt(kv.getOrDefault("step", "8"));
            p.tail = Integer.parseInt(kv.getOrDefault("tail", String.valueOf(9 * p.step)));
            String d = kv.getOrDefault("dead", "");
            if (d.startsWith("tick ")) p.tick = hex(d.substring(5).trim());
            List<int[]> acts = new ArrayList<>(Arrays.asList(new int[0], new int[]{SpecKeys.UP}, new int[]{SpecKeys.DOWN},
                    new int[]{SpecKeys.LEFT}, new int[]{SpecKeys.RIGHT}));
            boolean combo = kv.getOrDefault("combo", "0").equals("1");
            for (String f : kv.getOrDefault("fire", "").split(",")) {
                f = f.trim();
                if (f.isEmpty()) continue;
                int k = key(f);
                if (combo) {
                    acts.add(new int[]{SpecKeys.LEFT, k});
                    acts.add(new int[]{SpecKeys.RIGHT, k});
                } else {
                    acts.add(new int[]{k});
                }
            }
            p.actions = acts.toArray(new int[0][]);
            return p;
        }

        /** a key by its label on the keyboard; a few short names for the ones that are hard to type */
        static int key(String s) {
            switch (s) {
                case "Пробел": case "SP": return SpecKeys.SPACE;
                case "ВК": case "VK": return SpecKeys.ENTER;
                default:
                    int k = SpecKeys.byLabel(s);
                    if (k == SpecKeys.NONE) throw new IllegalArgumentException("нет клавиши " + s);
                    return k;
            }
        }

        static int hex(String s) {
            return Integer.parseInt(s, 16);
        }

        static int[] args(String[] p) {
            int[] a = new int[p.length - 1];
            for (int i = 1; i < p.length; i++) a[i - 1] = p[0].equals("rect") ? Integer.parseInt(p[i]) : (i == 1 ? hex(p[i]) : Integer.parseInt(p[i]));
            return a;
        }

        public boolean matches(String name, long crc) {
            if (crcs.contains(crc)) return true;
            if (name == null) return false;
            String n = name.toLowerCase(java.util.Locale.ROOT);
            if (n.endsWith(".rks")) n = n.substring(0, n.length() - 4);
            return names.contains(n);
        }

        // ---- reading the game

        /** the hero's top-left corner on the screen (pixels) */
        int hx(Specialist m) {
            return heroKind.equals("addr") ? (m.peek(hb) - 0x90) * 8 : m.peek(ha) + ox;
        }

        int hy(Specialist m) {
            return heroKind.equals("addr") ? m.peek(ha) : m.peek(hb) + oy;
        }

        static int ascii(Specialist m, int ad, int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                int c = m.peek(ad + i) - '0';
                if (c < 0 || c > 9) return -1;
                v = v * 10 + c;
            }
            return v;
        }

        /** a fingerprint of the screen bytes of a rectangle (x in pixels, by columns of 8) */
        static long rect(Specialist m, int[] r) {
            long h = 1125899906842597L;
            for (int c = r[0] / 8; c <= r[2] / 8; c++)
                for (int y = r[1]; y <= r[3]; y++) h = 31 * h + m.peek(0x9000 + c * 256 + y);
            return h;
        }

        long lives(Specialist m) {
            switch (livesKind) {
                case "byte": return m.peek(livesArg[0]);
                case "ascii": return ascii(m, livesArg[0], livesArg[1]);
                default: return rect(m, livesArg);
            }
        }

        long score(Specialist m) {
            return scoreKind.equals("ascii") ? ascii(m, scoreArg[0], scoreArg[1]) : rect(m, scoreArg);
        }

        boolean died(long before, long after) {
            return livesKind.equals("rect") ? before != after : after < before;
        }

        double gained(long before, long after) {
            if (scoreKind.equals("rect")) return before != after ? 1 : 0;
            long g = after - before;
            return g > 0 && g < 5000 ? g / 25.0 : 0;
        }
    }

    // ------------------------------------------------------------------ search settings

    public static final int H = 12, EXEC = 2, M = 64;
    static final double NEW_PLACE = 6, SEEN_PLACE = 1, SCORE_EVENT = 4, DEATH = -1_000_000, BORED = 0.6;

    /** one tried future: its key plan, its worth and where the hero went (screen pixels) */
    public static final class Future_ {
        public int[] seq;
        public double score;
        public int[] path; // x0,y0,x1,y1,… (pairs; fewer if he died early)
        public int kind;   // 0 tried, 1 death, 2 chosen
    }

    /** what to draw: the futures of the last planning */
    public static final class View {
        public final List<Future_> futures;
        public final double speedX;
        View(List<Future_> f, double s) {
            futures = f;
            speedX = s;
        }
    }

    public final Profile profile;
    private final byte[] rom;
    private final ExecutorService pool;
    private final int threads;
    private final Random rnd = new Random(1);
    private final ThreadLocal<Specialist> sim;
    private final ThreadLocal<short[]> buf = new ThreadLocal<short[]>() {
        @Override
        protected short[] initialValue() {
            return new short[4000];
        }
    };
    private final Map<Integer, Integer> visits = new java.util.concurrent.ConcurrentHashMap<>();
    private final ArrayDeque<int[]> recent = new ArrayDeque<>();
    private volatile double cx, cy;
    private boolean haveRecent;

    private int[] plan;
    private int stepIdx = EXEC;
    private long nextAt = -1;
    private int[] held = new int[0];
    private final Object keyLock = new Object();
    private volatile long yieldUntil;
    /** the futures of the last planning, for drawing (null until the first) */
    public volatile View view;
    public volatile int deaths;
    private long lives0 = Long.MIN_VALUE;

    public Explorer(byte[] rom, Profile profile, int threads) {
        this.rom = rom.clone();
        this.profile = profile;
        this.threads = Math.max(1, threads);
        this.pool = Executors.newFixedThreadPool(this.threads, r -> {
            Thread t = new Thread(r, "bot");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        this.sim = new ThreadLocal<Specialist>() { // (withInitial needs Android 8)
            @Override
            protected Specialist initialValue() {
                Specialist s = new Specialist();
                s.loadRom(Explorer.this.rom);
                s.setHistoryEnabled(false);
                return s;
            }
        };
        plan = new int[H];
        for (int i = 0; i < H; i++) plan[i] = rnd.nextInt(profile.actions.length);
    }

    static int cell(int x, int y) {
        return ((x >> 3) << 8) | ((y >> 3) & 0xFF);
    }

    private void act(Specialist m, int a, boolean down) {
        for (int k : profile.actions[a]) {
            if (down) m.keyDown(k);
            else m.keyUp(k);
        }
    }

    // ------------------------------------------------------------------ the player hands

    /** The player touched the controls: the bot lets go of its keys and waits {@code ms} after the last touch. */
    public void userInput(Specialist live, long ms) {
        synchronized (keyLock) {
            yieldUntil = System.currentTimeMillis() + ms;
            for (int k : held) live.keyUp(k);
            held = new int[0];
            view = null;
        }
    }

    public boolean yielding() {
        return System.currentTimeMillis() < yieldUntil;
    }

    /** Lets go of everything and stops the helpers. */
    public void stop(Specialist live) {
        synchronized (keyLock) {
            for (int k : held) live.keyUp(k);
            held = new int[0];
        }
        pool.shutdownNow();
        view = null;
    }

    /**
     * Called by the emulation thread before every frame of the live machine: at the end of each
     * step it changes the keys, every {@link #EXEC} steps it plans again (this waits for the
     * helpers — when the phone cannot keep up, the game runs slower).
     */
    public void beforeFrame(Specialist live) throws Exception {
        if (!yielding()) {
            synchronized (keyLock) {
                for (int k = 0; k < SpecKeys.COUNT; k++) {
                    if (!live.isKeyDown(k)) continue;
                    boolean mine = false;
                    for (int h : held) if (h == k) mine = true;
                    if (!mine) { // the player is holding something: hands off
                        yieldUntil = System.currentTimeMillis() + 2000;
                        for (int h : held) live.keyUp(h);
                        held = new int[0];
                        view = null;
                        break;
                    }
                }
            }
        }
        if (yielding()) {
            stepIdx = EXEC; // plan afresh when the player lets go
            nextAt = -1;
            return;
        }
        long now = live.time();
        if (nextAt >= 0 && now < nextAt) return;
        synchronized (keyLock) {
            for (int k : held) live.keyUp(k);
            held = new int[0];
        }
        if (nextAt >= 0) {
            visits.merge(cell(profile.hx(live), profile.hy(live)), 1, Integer::sum);
            recent.addLast(new int[]{profile.hx(live), profile.hy(live)});
            while (recent.size() > 400 / profile.step) recent.removeFirst();
            double sx = 0, sy = 0;
            for (int[] q : recent) {
                sx += q[0];
                sy += q[1];
            }
            cx = sx / recent.size();
            cy = sy / recent.size();
            haveRecent = true;
            long lv = profile.lives(live);
            if (lives0 != Long.MIN_VALUE && profile.died(lives0, lv)) deaths++;
            lives0 = lv;
        }
        if (stepIdx >= EXEC) {
            long t0 = System.nanoTime();
            plan = plan(live.saveState());
            double el = (System.nanoTime() - t0) / 1e9;
            View v = view;
            if (v != null) view = new View(v.futures, (double) M * H * profile.step / 50.0 / Math.max(el, 1e-6));
            stepIdx = 0;
        }
        int a = plan[stepIdx++];
        synchronized (keyLock) {
            if (yielding()) return;
            held = profile.actions[a].clone();
            for (int k : held) live.keyDown(k);
        }
        nextAt = live.time() + (long) profile.step * Specialist.CYCLES_PER_FRAME;
    }

    // ------------------------------------------------------------------ the search

    private Future_ eval(byte[] state, int[] seq) throws Exception {
        Specialist m = sim.get();
        short[] au = buf.get();
        m.loadState(state);
        long lives = profile.lives(m), sc0 = profile.score(m);
        Future_ r = new Future_();
        r.seq = seq;
        int[] path = new int[seq.length * 2];
        Set<Integer> seen = new HashSet<>();
        double s = 0;
        for (int i = 0; i < seq.length; i++) {
            int t0 = profile.tick >= 0 ? m.peek(profile.tick) : -1;
            long sBefore = profile.score(m);
            act(m, seq[i], true);
            for (int f = 0; f < profile.step; f++) m.runFrame(au);
            act(m, seq[i], false);
            boolean caught = profile.tick >= 0 && t0 != 0 && m.peek(profile.tick) == t0 && profile.score(m) == sBefore;
            int x = profile.hx(m), y = profile.hy(m);
            path[i * 2] = x;
            path[i * 2 + 1] = y;
            if (profile.died(lives, profile.lives(m)) || caught) {
                r.path = Arrays.copyOf(path, i * 2 + 2);
                r.score = DEATH + i;
                return r;
            }
            int c = cell(x, y);
            if (seen.add(c)) {
                int v = visits.getOrDefault(c, 0);
                s += Math.pow(0.95, i) * (v == 0 ? NEW_PLACE : SEEN_PLACE / Math.sqrt(1 + v));
            }
        }
        s += SCORE_EVENT * profile.gained(sc0, profile.score(m));
        if (haveRecent) s += BORED * Math.hypot(path[path.length - 2] - cx, path[path.length - 1] - cy) / 8.0;
        r.path = path;
        r.score = s;
        return r;
    }

    /** a future that looks good is lived on for the game's death delay: stand, go on, two random escapes; one survivor is enough */
    private boolean survives(byte[] state, int[] seq) throws Exception {
        Specialist m = sim.get();
        short[] au = buf.get();
        m.loadState(state);
        long lives = profile.lives(m);
        for (int a : seq) {
            act(m, a, true);
            for (int f = 0; f < profile.step; f++) m.runFrame(au);
            act(m, a, false);
        }
        byte[] mid = m.saveState();
        Random r = new Random(seq.length * 31L + seq[0]);
        int n = profile.actions.length;
        for (int tr = 0; tr < 4; tr++) {
            m.loadState(mid);
            int a = tr == 0 ? 0 : tr == 1 ? seq[seq.length - 1] : 1 + r.nextInt(n - 1);
            boolean lost = false;
            int t0 = profile.tick >= 0 ? m.peek(profile.tick) : -1;
            for (int f = 0; f < profile.tail && !lost; f++) {
                if (f % (profile.step + 4) == 0 && tr >= 2) {
                    act(m, a, false);
                    a = 1 + r.nextInt(n - 1);
                }
                act(m, a, true);
                m.runFrame(au);
                if (profile.tick >= 0 && f % profile.step == profile.step - 1) {
                    if (t0 != 0 && m.peek(profile.tick) == t0) lost = true;
                    t0 = m.peek(profile.tick);
                }
            }
            act(m, a, false);
            if (!lost && !profile.died(lives, profile.lives(m))) return true;
        }
        return false;
    }

    /** Plans from a saved state; returns the key plan and publishes the futures it tried. */
    public int[] plan(byte[] st) throws Exception {
        int n = profile.actions.length;
        List<Future<Future_>> fut = new ArrayList<>();
        int[] base = new int[H];
        System.arraycopy(plan, Math.min(EXEC, H), base, 0, H - EXEC);
        for (int i = H - EXEC; i < H; i++) base[i] = rnd.nextInt(n);
        for (int c = 0; c < M; c++) {
            int[] seq;
            if (c == 0) {
                seq = base.clone();
            } else if (c < M / 2) {
                seq = base.clone();
                int k = 1 + rnd.nextInt(3);
                for (int j = 0; j < k; j++) {
                    int p = rnd.nextInt(H), len = 1 + rnd.nextInt(4), v = rnd.nextInt(n);
                    for (int q = p; q < Math.min(H, p + len); q++) seq[q] = v;
                }
            } else {
                seq = new int[H];
                int v = rnd.nextInt(n);
                for (int q = 0; q < H; q++) {
                    if (rnd.nextInt(4) == 0) v = rnd.nextInt(n);
                    seq[q] = v;
                }
            }
            final int[] sq = seq;
            fut.add(pool.submit(() -> eval(st, sq)));
        }
        List<Future_> res = new ArrayList<>();
        for (Future<Future_> f : fut) res.add(f.get());
        res.sort((p, q) -> Double.compare(q.score, p.score));
        for (Future_ r : res) r.kind = r.score <= DEATH / 2 ? 1 : 0;
        Future_ best = res.get(0);
        // the best future that also lives on; a sure death only when nothing else is left
        int batch = threads * 2;
        boolean found = false;
        for (int k0 = 0; k0 < res.size() && !found; k0 += batch) {
            List<Future<Boolean>> ok = new ArrayList<>();
            for (int k = k0; k < Math.min(res.size(), k0 + batch); k++) {
                Future_ c = res.get(k);
                if (c.score <= DEATH / 2) break;
                ok.add(pool.submit(() -> survives(st, c.seq)));
            }
            if (ok.isEmpty()) break;
            for (int k = 0; k < ok.size(); k++) {
                Future_ c = res.get(k0 + k);
                if (ok.get(k).get()) {
                    if (!found) {
                        best = c;
                        found = true;
                    }
                } else {
                    c.kind = 1;
                }
            }
        }
        best.kind = 2;
        View old = view;
        view = new View(res, old == null ? 0 : old.speedX);
        return best.seq;
    }

    /** the places the hero has been, for a desktop picture or a test */
    public int placesVisited() {
        return visits.size();
    }
}
