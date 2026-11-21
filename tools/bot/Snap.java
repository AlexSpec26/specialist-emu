import com.alexspec.specialist.core.*;
import java.nio.file.*;
/** Snap rom rks script outState : runs a start script (as Probe) and saves the machine state */
public class Snap {
  public static void main(String[] a) throws Exception {
    Specialist m=new Specialist(); m.loadRom(Files.readAllBytes(Paths.get(a[0]))); m.hardReset();
    short[] au=new short[4000]; for(int i=0;i<50;i++) m.runFrame(au);
    m.loadRks(Files.readAllBytes(Paths.get(a[1])), true); m.setHistoryEnabled(false);
    for(String it: a[2].split(",")){ String[] p=it.trim().split(" "); int key=p[0].equals("k")?Probe.key(p[1]):SpecKeys.NONE; int n=Integer.parseInt(p[p.length-1]);
      if(key!=SpecKeys.NONE) m.keyDown(key); for(int i=0;i<n;i++) m.runFrame(au); if(key!=SpecKeys.NONE) m.keyUp(key); }
    Files.write(Paths.get(a[3]), m.saveState());
  }
}
