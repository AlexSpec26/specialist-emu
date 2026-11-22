import com.alexspec.specialist.core.*;
import java.nio.file.*; import java.util.*;
/** KeyScan rom state frames: for every key, holds it from the state and lists RAM bytes that end up different from an idle run */
public class KeyScan {
  public static void main(String[] a) throws Exception {
    byte[] rom=Files.readAllBytes(Paths.get(a[0])), st=Files.readAllBytes(Paths.get(a[1])); int n=Integer.parseInt(a[2]);
    Specialist m=new Specialist(); m.loadRom(rom); m.hardReset(); m.setHistoryEnabled(false); short[] au=new short[4000];
    int[] idle=run(m,st,SpecKeys.NONE,n,au);
    for(int k=0;k<SpecKeys.COUNT;k++){ int[] r=run(m,st,k,n,au); StringBuilder sb=new StringBuilder(); int c=0;
      for(int ad=0;ad<0x9000;ad++) if(r[ad]!=idle[ad]){ if(c<14) sb.append(String.format(" %04X:%02X>%02X",ad,idle[ad],r[ad])); c++; }
      if(c>0) System.out.println(k+" "+SpecKeys.label(k)+" ("+c+")"+sb); }
  }
  static int[] run(Specialist m, byte[] st, int k, int n, short[] au) throws Exception { m.loadState(st); if(k>=0) m.keyDown(k); for(int i=0;i<n;i++) m.runFrame(au); if(k>=0) m.keyUp(k); int[] r=new int[0x9000]; for(int i=0;i<r.length;i++) r[i]=m.peek(i); return r; }
}
