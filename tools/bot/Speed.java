import com.alexspec.specialist.core.*;
import java.nio.file.*;
public class Speed {
  public static void main(String[] a) throws Exception {
    Specialist m = new Specialist();
    m.loadRom(Files.readAllBytes(Paths.get(a[0]))); m.hardReset();
    short[] au = new short[4000];
    for (int i=0;i<50;i++) m.runFrame(au);
    m.loadRks(Files.readAllBytes(Paths.get(a[1])), true);
    for (int i=0;i<300;i++) m.runFrame(au);
    m.setHistoryEnabled(false);
    long t=System.nanoTime(); int n=3000; for (int i=0;i<n;i++) m.runFrame(au);
    double s=(System.nanoTime()-t)/1e9; System.out.printf("frames/s %.0f (x%.0f realtime at 50fps)%n", n/s, n/s/50);
    byte[] st=m.saveState(); t=System.nanoTime(); for(int i=0;i<1000;i++){st=m.saveState(); m.loadState(st);} 
    System.out.printf("state %d bytes, save+load %.3f ms%n", st.length, (System.nanoTime()-t)/1e6/1000);
  }
}
