import com.alexspec.specialist.core.*;
import java.nio.file.*; import java.util.*; import java.util.Properties; import java.util.concurrent.*;
import java.awt.Graphics2D; import java.awt.Color; import java.awt.Font; import java.awt.BasicStroke; import java.awt.RenderingHints; import java.awt.image.BufferedImage; import javax.imageio.ImageIO; import java.io.File;

/**
 * Explorer bot prototype: from the current machine state it tries many short futures on copies of
 * the machine, scores them (new places reached, score up, no lost life), plays the best one a
 * little, and plans again. Writes the played frames with the tried futures drawn over them.
 * Bot rom state outdir seconds
 */
public class Bot {
  // ---- game profile (prof/*.txt): where the hero is, lives and score
  static String heroKind; static int HA, HB, OX, OY;
  static String livesKind, scoreKind; static int[] livesArg, scoreArg;
  static int[][] ACT; static int STEP = 8; static final int H = 12, EXEC = 2, M = 64;
  /** reward weights: a never-visited place is worth a lot, a lost life outweighs everything */
  static final double NEW_PLACE = 6, SEEN_PLACE = 1, SCORE_EVENT = 4, DEATH = -1_000_000, BORED = 0.6;
  /** where the hero has been lately (about 8 s): a plan that ends far from there is worth more — boredom, so he does not hang about one corner */
  static final ArrayDeque<int[]> recent = new ArrayDeque<>(); static volatile double cx, cy;

  static int hx(Specialist m){ return heroKind.equals("addr") ? (m.peek(HB) - 0x90) * 8 : m.peek(HA) + OX; }
  static int hy(Specialist m){ return heroKind.equals("addr") ? m.peek(HA) : m.peek(HB) + OY; }
  static int px(int x){ return x; } static int py(int y){ return y; }
  static int hexa(String s){ return Integer.parseInt(s, 16); }
  static int ascii(Specialist m, int ad, int n){ int v=0; for(int i=0;i<n;i++){ int c=m.peek(ad+i)-48; if(c<0||c>9) return -1; v=v*10+c; } return v; }
  /** screen bytes of a rectangle (x in pixels, rounded to columns of 8) */
  static long rect(Specialist m, int[] r){ long h=1125899906842597L; for(int c=r[0]/8;c<=r[2]/8;c++) for(int y=r[1];y<=r[3];y++) h=31*h+m.peek(0x9000+c*256+y); return h; }
  static long lives(Specialist m){ switch(livesKind){ case "byte": return m.peek(livesArg[0]); case "ascii": return ascii(m,livesArg[0],livesArg[1]); default: return rect(m,livesArg);} }
  static long score(Specialist m){ return scoreKind.equals("ascii") ? ascii(m,scoreArg[0],scoreArg[1]) : rect(m,scoreArg); }
  static int TICK = -1;
  /** how long after the fatal moment the game takes away the life (death animation): futures are lived on that long */
  static int TAIL;
  /** caught: some games freeze their whole loop the moment the hero dies, long before the lives counter
   *  goes down; a tick that stood still for a whole step (with no score) means that */
  static final class Tick { int last = -1; }
  static boolean frozen(Specialist m, int before){ return TICK >= 0 && m.peek(TICK) == before; }
  static boolean died(long before, long after){ return livesKind.equals("rect") ? before != after : after < before; }
  static double gained(long before, long after){ if(scoreKind.equals("rect")) return before != after ? 1 : 0; long g=after-before; return g>0 && g<5000 ? g/25.0 : 0; }

  static Properties prof = new Properties();
  static void profile(String file) throws Exception {
    try (java.io.Reader r = Files.newBufferedReader(Paths.get(file), java.nio.charset.StandardCharsets.UTF_8)) { prof.load(r); }
    String[] h = prof.getProperty("hero").split(" "); heroKind = h[0]; HA = hexa(h[1]); HB = hexa(h[2]);
    if (h.length > 3) { OX = Integer.parseInt(h[3]); OY = Integer.parseInt(h[4]); }
    String[] l = prof.getProperty("lives").split(" "); livesKind = l[0]; livesArg = args(l);
    String[] sc = prof.getProperty("score").split(" "); scoreKind = sc[0]; scoreArg = args(sc);
    STEP = Integer.parseInt(prof.getProperty("step", "8"));
    TAIL = Integer.parseInt(prof.getProperty("tail", String.valueOf(9 * STEP)));
    String d = prof.getProperty("dead", ""); if (d.startsWith("tick ")) TICK = hexa(d.substring(5).trim());
    List<int[]> acts = new ArrayList<>(Arrays.asList(new int[]{}, new int[]{SpecKeys.UP}, new int[]{SpecKeys.DOWN}, new int[]{SpecKeys.LEFT}, new int[]{SpecKeys.RIGHT}));
    for (String f : prof.getProperty("fire", "").split(",")) { if (f.isEmpty()) continue; int k = Probe.key(f.trim());
      if (prof.getProperty("combo", "0").equals("1")) { acts.add(new int[]{SpecKeys.LEFT, k}); acts.add(new int[]{SpecKeys.RIGHT, k}); } else acts.add(new int[]{k}); }
    ACT = acts.toArray(new int[0][]);
  }
  static int[] args(String[] p){ int[] a = new int[p.length - 1]; for (int i = 1; i < p.length; i++) a[i-1] = p[0].equals("rect") ? Integer.parseInt(p[i]) : (i == 1 ? hexa(p[i]) : Integer.parseInt(p[i])); return a; }

  static byte[] rom;
  static final Map<Integer,Integer> visits = new ConcurrentHashMap<>();
  static int cell(int x,int y){ return ((x>>3)<<8)|((y>>3)&0xFF); }

  static final class Result { int[] seq; double score; int[][] path; }

  static final ThreadLocal<Specialist> sim = ThreadLocal.withInitial(() -> { Specialist s=new Specialist(); s.loadRom(rom); s.setHistoryEnabled(false); return s; });
  static final ThreadLocal<short[]> buf = ThreadLocal.withInitial(() -> new short[4000]);

  static void act(Specialist m, int a, boolean down){ for(int k: ACT[a]) { if(down) m.keyDown(k); else m.keyUp(k);} }

  static Result eval(byte[] state, int[] seq) throws Exception {
    Specialist m = sim.get(); short[] au = buf.get();
    m.loadState(state);
    long lives = lives(m), sc0 = score(m);
    Result r = new Result(); r.seq = seq; r.path = new int[seq.length][];
    Set<Integer> seen = new HashSet<>(); double s = 0;
    for (int i = 0; i < seq.length; i++) {
      int t0 = TICK >= 0 ? m.peek(TICK) : -1; long sBefore = score(m);
      act(m, seq[i], true);
      for (int f = 0; f < STEP; f++) m.runFrame(au);
      act(m, seq[i], false);
      boolean caught = frozen(m, t0) && score(m) == sBefore && t0 != 0;
      int x = hx(m), y = hy(m);
      r.path[i] = new int[]{x, y};
      if (died(lives, lives(m)) || caught) { r.score = DEATH + i; return r; }
      int c = cell(x, y);
      if (seen.add(c)) { int v = visits.getOrDefault(c, 0); s += Math.pow(0.95, i) * (v == 0 ? NEW_PLACE : SEEN_PLACE / Math.sqrt(1 + v)); }
    }
    s += SCORE_EVENT * gained(sc0, score(m));
    int[] end = r.path[seq.length - 1];
    if (!recent.isEmpty()) s += BORED * Math.hypot(end[0] - cx, end[1] - cy) / 8.0;
    r.score = s; return r;
  }

  /** a future that looks good is also run on, idle, for 60 frames: a death it already sealed shows up then */
  static boolean survives(byte[] state, int[] seq) throws Exception {
    Specialist m = sim.get(); short[] au = buf.get();
    m.loadState(state); long lives = lives(m);
    for (int i = 0; i < seq.length; i++) { act(m, seq[i], true); for (int f = 0; f < STEP; f++) m.runFrame(au); act(m, seq[i], false); }
    byte[] mid = m.saveState();
    Random r = new Random(seq.length * 31L + seq[0]);
    for (int tr = 0; tr < 4; tr++) { // idle, keep going, two random escapes: one survivor is enough
      m.loadState(mid);
      int a = tr == 0 ? 0 : tr == 1 ? seq[seq.length - 1] : 1 + r.nextInt(ACT.length - 1);
      boolean lost = false; int t0 = TICK >= 0 ? m.peek(TICK) : -1;
      for (int f = 0; f < TAIL && !lost; f++) { if (f % (STEP + 4) == 0 && tr >= 2) { act(m, a, false); a = 1 + r.nextInt(ACT.length - 1); }
        act(m, a, true); m.runFrame(au);
        if (TICK >= 0 && f % STEP == STEP - 1) { if (frozen(m, t0) && t0 != 0) lost = true; t0 = m.peek(TICK); } }
      if (lost) { act(m, a, false); continue; }
      act(m, a, false);
      if (!died(lives, lives(m))) return true;
    }
    return false;
  }

  public static void main(String[] a) throws Exception {
    rom = Files.readAllBytes(Paths.get(a[0])); profile(a[4]);
    Specialist m = new Specialist(); m.loadRom(rom); m.hardReset(); m.loadState(Files.readAllBytes(Paths.get(a[1]))); m.setHistoryEnabled(false);
    String out = a[2]; int seconds = Integer.parseInt(a[3]);
    new File(out).mkdirs();
    int threads = Runtime.getRuntime().availableProcessors();
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    Random rnd = new Random(1);
    short[] au = new short[4000]; int[] pix = new int[Specialist.WIDTH * Specialist.HEIGHT];
    int[] plan = new int[H]; for (int i = 0; i < H; i++) plan[i] = rnd.nextInt(ACT.length);
    int frame = 0, img = 0, deaths = 0; long lives0 = lives(m);
    long sims = 0, t0 = System.nanoTime();
    while (frame < seconds * 50) {
      byte[] st = m.saveState();
      List<Future<Result>> fut = new ArrayList<>();
      for (int c = 0; c < M; c++) {
        int[] seq = new int[H];
        if (c == 0) seq = plan.clone();
        else if (c < M / 2) { seq = plan.clone(); int k = 1 + rnd.nextInt(3); for (int j = 0; j < k; j++) { int p = rnd.nextInt(H), len = 1 + rnd.nextInt(4), v = rnd.nextInt(ACT.length); for (int q = p; q < Math.min(H, p + len); q++) seq[q] = v; } }
        else { int v = rnd.nextInt(ACT.length); for (int q = 0; q < H; q++) { if (rnd.nextInt(4) == 0) v = rnd.nextInt(ACT.length); seq[q] = v; } }
        final int[] sq = seq; fut.add(pool.submit(() -> eval(st, sq)));
      }
      List<Result> res = new ArrayList<>(); for (Future<Result> f : fut) res.add(f.get());
      sims += (long) M * H * STEP;
      res.sort((p, q) -> Double.compare(q.score, p.score));
      Result best = res.get(0);
      // the best future that is also lived on (death animation, enemy closing in) without losing a life;
      // all of them are tried in order if need be — a sure death is taken only when nothing else is left
      int batch = threads * 2; boolean found = false;
      for (int k0 = 0; k0 < res.size() && !found; k0 += batch) {
        List<Future<Boolean>> ok = new ArrayList<>();
        for (int k = k0; k < Math.min(res.size(), k0 + batch); k++) { Result c = res.get(k); if (c.score <= DEATH / 2) break; ok.add(pool.submit(() -> survives(st, c.seq))); }
        if (ok.isEmpty()) break;
        for (int k = 0; k < ok.size(); k++) {
          Result c = res.get(k0 + k);
          if (ok.get(k).get()) { if (!found) { best = c; found = true; } }
          else c.score = DEATH / 2 + 1; // sealed death: drawn red
        }
      }
      // play the first EXEC steps of the best future
      for (int i = 0; i < EXEC; i++) {
        act(m, best.seq[i], true);
        for (int f = 0; f < STEP; f++) {
          m.runFrame(au); frame++;
          if (frame % 3 == 0) draw(m, pix, res, best, out + String.format("/f%05d.png", img++), deaths);
        }
        act(m, best.seq[i], false);
        visits.merge(cell(hx(m), hy(m)), 1, Integer::sum);
        recent.addLast(new int[]{hx(m), hy(m)}); while (recent.size() > 400 / STEP) recent.removeFirst();
        double sx = 0, sy = 0; for (int[] q : recent) { sx += q[0]; sy += q[1]; } cx = sx / recent.size(); cy = sy / recent.size();
      }
      long lv = lives(m); if (died(lives0, lv)) { deaths++; int neg = 0; for (Result q : res) if (q.score < 0) neg++;
        System.out.printf("death at frame %d: chosen score %.1f, %d of %d futures died%n", frame, best.score, neg, res.size()); } lives0 = lv;
      plan = new int[H]; System.arraycopy(best.seq, EXEC, plan, 0, H - EXEC); for (int i = H - EXEC; i < H; i++) plan[i] = rnd.nextInt(ACT.length);
    }
    pool.shutdown(); Files.write(Paths.get(out + "/end.st"), m.saveState());
    double el = (System.nanoTime() - t0) / 1e9;
    System.out.printf("played %d s in %.1f s, %d sim frames (%.0f x realtime of search), deaths %d, cells %d%n", seconds, el, sims, sims / 50.0 / el, deaths, visits.size());
  }

  static void draw(Specialist m, int[] pix, List<Result> res, Result best, String file, int deaths) throws Exception {
    m.render(pix, Specialist.COLOR_WHITE);
    BufferedImage im = new BufferedImage(Specialist.WIDTH, Specialist.HEIGHT, BufferedImage.TYPE_INT_RGB);
    im.setRGB(0, 0, Specialist.WIDTH, Specialist.HEIGHT, pix, 0, Specialist.WIDTH);
    BufferedImage big = new BufferedImage(Specialist.WIDTH * 2, Specialist.HEIGHT * 2, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = big.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
    g.drawImage(im, 0, 0, big.getWidth(), big.getHeight(), null);
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setStroke(new BasicStroke(2f));
    for (Result r : res) {
      if (r == best) continue;
      g.setColor(r.score < 0 ? new Color(255, 60, 60, 110) : new Color(80, 170, 255, 70));
      path(g, r);
    }
    g.setStroke(new BasicStroke(3f)); g.setColor(new Color(80, 255, 120, 230)); path(g, best);
    g.setColor(Color.YELLOW); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
    g.drawString(prof.getProperty("game").replace(".rks", "") + " · бот: " + res.size() + " вариантов на " + (H * STEP) + " кадров вперёд · потерь жизни: " + deaths, 10, 18);
    g.dispose(); ImageIO.write(big, "png", new File(file));
  }
  static void path(Graphics2D g, Result r) {
    int lx = -1, ly = -1;
    for (int[] p : r.path) { if (p == null) break; if (p[0] == (heroKind.equals("addr") ? -0x480 : OX) && p[1] == (heroKind.equals("addr") ? 0 : OY)) continue; int x = px(p[0]) * 2 + 6, y = py(p[1]) * 2 + 6; if (lx >= 0) g.drawLine(lx, ly, x, y); lx = x; ly = y; }
    if (lx >= 0) g.fillOval(lx - 3, ly - 3, 6, 6);
  }
}
