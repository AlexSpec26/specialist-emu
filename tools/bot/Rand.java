import com.alexspec.specialist.core.*;
import java.nio.file.*; import java.io.*; import java.util.*;
/** Rand rom state out frames seed: random joystick play (hold a random direction/fire 5-30 frames), RAM each 5 frames, shots every 500 */
public class Rand {
  public static void main(String[] a) throws Exception {
    Specialist m=new Specialist(); m.loadRom(Files.readAllBytes(Paths.get(a[0]))); m.hardReset(); m.loadState(Files.readAllBytes(Paths.get(a[1]))); m.setHistoryEnabled(false);
    short[] au=new short[4000]; String out=a[2]; int N=Integer.parseInt(a[3]); Random rnd=new Random(Long.parseLong(a[4]));
    int[] keys={SpecKeys.UP,SpecKeys.DOWN,SpecKeys.LEFT,SpecKeys.RIGHT,SpecKeys.byLabel(a.length>5?a[5]:"Z"),SpecKeys.byLabel(a.length>6?a[6]:"X")};
    DataOutputStream ram=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out+"/ram.bin")));
    int f=0, shot=0;
    while(f<N){ int k=keys[rnd.nextInt(keys.length)]; int n=5+rnd.nextInt(25); m.keyDown(k);
      for(int i=0;i<n&&f<N;i++,f++){ m.runFrame(au); if(f%5==0) for(int ad=0;ad<0x9000;ad++) ram.write(m.peek(ad)); if(f%500==0){Probe.m=m;Probe.shot(out+"/r"+(shot++)+".png");} }
      m.keyUp(k); }
    ram.close();
  }
}
