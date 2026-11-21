import com.alexspec.specialist.core.*;
import java.nio.file.*; import java.io.*;
/** Ram rom state out script : from a saved state, runs a key script dumping RAM (0000-8FFF) every frame; "s" = screenshot */
public class Ram {
  public static void main(String[] a) throws Exception {
    Specialist m=new Specialist(); m.loadRom(Files.readAllBytes(Paths.get(a[0]))); m.hardReset(); m.loadState(Files.readAllBytes(Paths.get(a[1]))); m.setHistoryEnabled(false);
    short[] au=new short[4000]; String out=a[2]; int shot=0;
    DataOutputStream ram=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out+"/ram.bin")));
    for(String it: a[3].split(",")){ String[] p=it.trim().split(" ");
      if(p[0].equals("s")){ Probe.m=m; Probe.shot(out+"/s"+(shot++)+".png"); continue; }
      int key=p[0].equals("k")?Probe.key(p[1]):SpecKeys.NONE; int n=Integer.parseInt(p[p.length-1]);
      if(key!=SpecKeys.NONE) m.keyDown(key); for(int i=0;i<n;i++){ m.runFrame(au); for(int ad=0;ad<0x9000;ad++) ram.write(m.peek(ad)); } if(key!=SpecKeys.NONE) m.keyUp(key); }
    ram.close();
  }
}
