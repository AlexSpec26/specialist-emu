import com.alexspec.specialist.core.*;
import java.nio.file.*;
/** prints per frame: RAM x,y of hero record and the x-range of lit pixels in a screen band */
public class Cal {
  public static void main(String[] a) throws Exception {
    Specialist m=new Specialist(); m.loadRom(Files.readAllBytes(Paths.get(a[0]))); m.hardReset(); m.loadState(Files.readAllBytes(Paths.get(a[1]))); m.setHistoryEnabled(false);
    short[] au=new short[4000]; int[] pix=new int[Specialist.WIDTH*Specialist.HEIGHT]; int[] ref=null;
    int key=Probe.key(a[2]); int y0=Integer.parseInt(a[3]), y1=Integer.parseInt(a[4]);
    m.keyDown(key);
    for(int f=0;f<120;f++){ m.runFrame(au); m.render(pix,Specialist.COLOR_WHITE);
      int lo=999,hi=-1; for(int y=y0;y<y1;y++) for(int x=0;x<Specialist.WIDTH;x++) if((pix[y*Specialist.WIDTH+x]&0xFF)>128){lo=Math.min(lo,x);hi=Math.max(hi,x);} 
      if(f%10==0) System.out.println(f+" ram x="+m.peek(0x84B6)+" y="+m.peek(0x84B5)+" band "+lo+".."+hi); }
  }
}
