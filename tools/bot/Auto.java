import com.alexspec.specialist.core.*;
import java.nio.file.*; import java.util.*;
/** Auto rom state frames: finds hero X/Y bytes (move with ←/→, ↑/↓) and where the hero is drawn */
public class Auto {
  static Specialist m; static short[] au=new short[4000];
  public static void main(String[] a) throws Exception {
    m=new Specialist(); m.loadRom(Files.readAllBytes(Paths.get(a[0]))); m.hardReset(); m.setHistoryEnabled(false);
    byte[] st=Files.readAllBytes(Paths.get(a[1])); int n=Integer.parseInt(a[2]);
    axis("X", st, n, SpecKeys.LEFT, SpecKeys.RIGHT, true);
    axis("Y", st, n, SpecKeys.UP, SpecKeys.DOWN, false);
  }
  static int[] ram; static int[] pix;
  static void run(byte[] st,int k,int n) throws Exception { m.loadState(st); if(k>=0) m.keyDown(k); for(int i=0;i<n;i++) m.runFrame(au); if(k>=0) m.keyUp(k);
    ram=new int[0x9000]; for(int i=0;i<ram.length;i++) ram[i]=m.peek(i); pix=new int[Specialist.WIDTH*Specialist.HEIGHT]; m.render(pix,Specialist.COLOR_WHITE); }
  static void axis(String name, byte[] st, int n, int kMinus, int kPlus, boolean horiz) throws Exception {
    // the hero moves over several lengths of time: a byte that follows the key every time is its coordinate
    List<int[]> pts=new ArrayList<>(); Map<Integer,Integer> score=new HashMap<>();
    int[][] runs=new int[6][]; int[][] pics=new int[6][];
    int[] lens={n/3, 2*n/3, n};
    for(int li=0;li<3;li++){ run(st,kMinus,lens[li]); runs[li*2]=ram; pics[li*2]=pix; run(st,kPlus,lens[li]); runs[li*2+1]=ram; pics[li*2+1]=pix; }
    for(int ad=0;ad<0x9000;ad++){ boolean ok=true; for(int li=0;li<3;li++) if(!(runs[li*2][ad]<runs[li*2+1][ad])) ok=false;
      if(ok && runs[0][ad]>=runs[2][ad] && runs[2][ad]>=runs[4][ad] && runs[1][ad]<=runs[3][ad] && runs[3][ad]<=runs[5][ad] && runs[4][ad]!=runs[5][ad]) {
        System.out.printf("%s candidate %04X: minus %d %d %d  plus %d %d %d%n", name, ad, runs[0][ad],runs[2][ad],runs[4][ad],runs[1][ad],runs[3][ad],runs[5][ad]); } }
    // where on screen: pixels that differ between the − and + runs, per length
    for(int li=0;li<3;li++){ int[] p=pics[li*2], q=pics[li*2+1]; long sx=0,sy=0,c=0; int x0=999,x1=-1,y0=999,y1=-1;
      for(int y=0;y<Specialist.HEIGHT;y++) for(int x=0;x<Specialist.WIDTH;x++) if(p[y*Specialist.WIDTH+x]!=q[y*Specialist.WIDTH+x]){ c++; x0=Math.min(x0,x);x1=Math.max(x1,x);y0=Math.min(y0,y);y1=Math.max(y1,y);} 
      System.out.printf("%s screen diff len %d: %d px, x %d..%d y %d..%d%n", name, lens[li], c, x0,x1,y0,y1); }
  }
}
