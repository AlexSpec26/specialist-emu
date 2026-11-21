import com.alexspec.specialist.core.*;
import java.nio.file.*; import java.io.*; import java.awt.image.BufferedImage; import javax.imageio.ImageIO;
/** Probe rom rks outdir script: items "f N", "k KEY N" (hold key N frames, dumping RAM each frame), "s" shot */
public class Probe {
  static Specialist m; static short[] au=new short[4000]; static int[] pix=new int[Specialist.WIDTH*Specialist.HEIGHT];
  public static void main(String[] a) throws Exception {
    m=new Specialist(); m.loadRom(Files.readAllBytes(Paths.get(a[0]))); m.hardReset();
    for(int i=0;i<50;i++) m.runFrame(au);
    m.loadRks(Files.readAllBytes(Paths.get(a[1])), true);
    String out=a[2]; int shot=0;
    DataOutputStream ram=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out+"/ram.bin")));
    PrintWriter log=new PrintWriter(out+"/log.txt");
    int frame=0;
    for(String it: a[3].split(",")){
      String[] p=it.trim().split(" ");
      if(p[0].equals("s")){ shot(out+"/s"+(shot++)+".png"); log.println(frame+" shot "+(shot-1)); continue; }
      int key = p[0].equals("k") ? key(p[1]) : SpecKeys.NONE;
      int n=Integer.parseInt(p[p.length-1]);
      if(key!=SpecKeys.NONE) m.keyDown(key);
      for(int i=0;i<n;i++){ m.runFrame(au); frame++; for(int ad=0;ad<0x9000;ad++) ram.write(m.peek(ad)); log.println(frame+" "+(p[0].equals("k")?p[1]:"-")); }
      if(key!=SpecKeys.NONE) m.keyUp(key);
    }
    ram.close(); log.close();
  }
  static int key(String s){ switch(s){case "R":return SpecKeys.RIGHT;case "L":return SpecKeys.LEFT;case "U":return SpecKeys.UP;case "D":return SpecKeys.DOWN;case "SP":return SpecKeys.SPACE;case "VK":return SpecKeys.ENTER;} int k=SpecKeys.byLabel(s); if(k==SpecKeys.NONE) throw new RuntimeException(s); return k; }
  static void shot(String f) throws Exception { m.render(pix, Specialist.COLOR_WHITE); BufferedImage im=new BufferedImage(Specialist.WIDTH,Specialist.HEIGHT,BufferedImage.TYPE_INT_RGB); im.setRGB(0,0,Specialist.WIDTH,Specialist.HEIGHT,pix,0,Specialist.WIDTH); ImageIO.write(im,"png",new File(f)); }
}
