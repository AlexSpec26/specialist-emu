import com.alexspec.specialist.core.*;
import java.nio.file.*; import java.util.*; import java.util.concurrent.*;
import java.awt.Graphics2D; import java.awt.Color; import java.awt.Font; import java.awt.BasicStroke; import java.awt.RenderingHints; import java.awt.image.BufferedImage; import javax.imageio.ImageIO; import java.io.File;

/**
 * Explorer bot prototype: from the current machine state it tries many short futures on copies of
 * the machine, scores them (new places reached, score up, no lost life), plays the best one a
 * little, and plans again. Writes the played frames with the tried futures drawn over them.
 * Bot rom state outdir seconds
 */
public class Bot {
  // ---- game profile: Lode Runner (А. Щербина, 1990), cursor control
  static final int HX = 0x84B6, HY = 0x84B5, LIVES = 0x8409, SCORE = 0x840C; // score: 7 ASCII digits
  static int px(int x){ return x + 64; } static int py(int y){ return y + 28; }
  static int score(Specialist m){ int v=0; for(int i=0;i<7;i++){ int c=m.peek(SCORE+i)-48; if(c<0||c>9) return 0; v=v*10+c; } return v; }
  static final int F = SpecKeys.SPACE;
  static final int[][] ACT = {{}, {SpecKeys.UP}, {SpecKeys.DOWN}, {SpecKeys.LEFT}, {SpecKeys.RIGHT}, {SpecKeys.LEFT, F}, {SpecKeys.RIGHT, F}};
  static final int STEP = 8, H = 12, EXEC = 2, M = 64;

  static byte[] rom;
  static final Map<Integer,Integer> visits = new ConcurrentHashMap<>();
  static int cell(int x,int y){ return ((x>>3)<<8)|(y>>3); }

  static final class Result { int[] seq; double score; int[][] path; }

  static final ThreadLocal<Specialist> sim = ThreadLocal.withInitial(() -> { Specialist s=new Specialist(); s.loadRom(rom); s.setHistoryEnabled(false); return s; });
  static final ThreadLocal<short[]> buf = ThreadLocal.withInitial(() -> new short[4000]);

  static void act(Specialist m, int a, boolean down){ for(int k: ACT[a]) { if(down) m.keyDown(k); else m.keyUp(k);} }

  static Result eval(byte[] state, int[] seq) throws Exception {
    Specialist m = sim.get(); short[] au = buf.get();
    m.loadState(state);
    int lives = m.peek(LIVES), sc0 = score(m);
    Result r = new Result(); r.seq = seq; r.path = new int[seq.length][];
    Set<Integer> seen = new HashSet<>(); double s = 0;
    for (int i = 0; i < seq.length; i++) {
      act(m, seq[i], true);
      for (int f = 0; f < STEP; f++) m.runFrame(au);
      act(m, seq[i], false);
      int x = m.peek(HX), y = m.peek(HY);
      r.path[i] = new int[]{x, y};
      if (m.peek(LIVES) < lives) { r.score = -1000 + i; return r; }
      int c = cell(x, y);
      if (seen.add(c)) s += Math.pow(0.93, i) / Math.sqrt(1 + visits.getOrDefault(c, 0));
    }
    int gain = score(m) - sc0;
    if (gain > 0 && gain < 5000) s += 3.0 * gain / 25.0;
    r.score = s; return r;
  }

  /** a future that looks good is also run on, idle, for 60 frames: a death it already sealed shows up then */
  static boolean survives(byte[] state, int[] seq) throws Exception {
    Specialist m = sim.get(); short[] au = buf.get();
    m.loadState(state); int lives = m.peek(LIVES);
    for (int i = 0; i < seq.length; i++) { act(m, seq[i], true); for (int f = 0; f < STEP; f++) m.runFrame(au); act(m, seq[i], false); }
    byte[] mid = m.saveState();
    Random r = new Random(seq.length * 31L + seq[0]);
    for (int tr = 0; tr < 4; tr++) { // idle, keep going, two random escapes: one survivor is enough
      m.loadState(mid);
      int a = tr == 0 ? 0 : tr == 1 ? seq[seq.length - 1] : 1 + r.nextInt(ACT.length - 1);
      for (int f = 0; f < 72; f++) { if (f % 12 == 0 && tr >= 2) { act(m, a, false); a = 1 + r.nextInt(ACT.length - 1); } act(m, a, true); m.runFrame(au); }
      act(m, a, false);
      if (m.peek(LIVES) >= lives) return true;
    }
    return false;
  }

  public static void main(String[] a) throws Exception {
    rom = Files.readAllBytes(Paths.get(a[0]));
    Specialist m = new Specialist(); m.loadRom(rom); m.hardReset(); m.loadState(Files.readAllBytes(Paths.get(a[1]))); m.setHistoryEnabled(false);
    String out = a[2]; int seconds = Integer.parseInt(a[3]);
    new File(out).mkdirs();
    int threads = Runtime.getRuntime().availableProcessors();
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    Random rnd = new Random(1);
    short[] au = new short[4000]; int[] pix = new int[Specialist.WIDTH * Specialist.HEIGHT];
    int[] plan = new int[H]; for (int i = 0; i < H; i++) plan[i] = rnd.nextInt(ACT.length);
    int frame = 0, img = 0, deaths = 0, lives0 = m.peek(LIVES);
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
      for (int k = 0; k < Math.min(8, res.size()); k++) {
        Result c = res.get(k); if (c.score < 0) break;
        if (survives(st, c.seq)) { best = c; break; }
        c.score = -500; // sealed death: drawn red
      }
      // play the first EXEC steps of the best future
      for (int i = 0; i < EXEC; i++) {
        act(m, best.seq[i], true);
        for (int f = 0; f < STEP; f++) {
          m.runFrame(au); frame++;
          if (frame % 3 == 0) draw(m, pix, res, best, out + String.format("/f%05d.png", img++), deaths);
        }
        act(m, best.seq[i], false);
        visits.merge(cell(m.peek(HX), m.peek(HY)), 1, Integer::sum);
      }
      if (m.peek(LIVES) < lives0) { deaths++; lives0 = m.peek(LIVES); }
      if (m.peek(LIVES) > lives0) lives0 = m.peek(LIVES);
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
    g.drawString("бот: " + res.size() + " вариантов на " + (H * STEP) + " кадров вперёд · потерь жизни: " + deaths, 10, 18);
    g.dispose(); ImageIO.write(big, "png", new File(file));
  }
  static void path(Graphics2D g, Result r) {
    int lx = -1, ly = -1;
    for (int[] p : r.path) { if (p == null) break; if (p[0] == 0 && p[1] == 0) continue; int x = px(p[0]) * 2 + 6, y = py(p[1]) * 2 + 8; if (lx >= 0) g.drawLine(lx, ly, x, y); lx = x; ly = y; }
    if (lx >= 0) g.fillOval(lx - 3, ly - 3, 6, 6);
  }
}
