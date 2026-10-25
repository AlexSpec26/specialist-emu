import com.alexspec.specialist.core.*;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
/** Runs every game with Monitor-2 and with the shell ROM, same keys, and compares the screens. */
public class ShellCompat {
  static short[] au=new short[4000];
  static Specialist boot(byte[] rom, byte[] game) throws Exception {
    Specialist m=new Specialist(); m.loadRom(rom); m.hardReset();
    for(int i=0;i<50;i++) m.runFrame(au);
    m.loadRks(game,true);
    for(int a=0x9000;a<0xC000;a++) m.write(a,0);
    return m;
  }
  static int hash(Specialist m){ int h=1; for(int a=0x9000;a<0xC000;a++) h=h*31+m.peek(a); return h; }
  static void shot(Specialist a, Specialist b, String f) throws Exception {
    int W=Specialist.WIDTH,H=Specialist.HEIGHT; int[] p=new int[W*H];
    BufferedImage im=new BufferedImage(W*2+8,H,BufferedImage.TYPE_INT_RGB);
    a.render(p,0); im.setRGB(0,0,W,H,p,0,W); b.render(p,0); im.setRGB(W+8,0,W,H,p,0,W);
    javax.imageio.ImageIO.write(im,"png",new java.io.File(f));
  }
  public static void main(String[] args) throws Exception {
    byte[] r1=Files.readAllBytes(Paths.get(args[0])), r2=Files.readAllBytes(Paths.get(args[1]));
    String outDir=args[2];
    int ok=0, diff=0; List<String> bad=new ArrayList<>();
    for (int gi=3; gi<args.length; gi++) {
      byte[] g=Files.readAllBytes(Paths.get(args[gi]));
      String name=Paths.get(args[gi]).getFileName().toString().replace(".rks","");
      Specialist a,b;
      try { a=boot(r1,g); b=boot(r2,g); } catch(Exception e){ System.out.println(name+": load error"); continue; }
      int[] keys={SpecKeys.ENTER,SpecKeys.SPACE,SpecKeys.byLabel("1"),SpecKeys.RIGHT,SpecKeys.LEFT,SpecKeys.UP,SpecKeys.DOWN,SpecKeys.byLabel("F1"),SpecKeys.byLabel("N"),SpecKeys.byLabel("Y")};
      Random r=new Random(gi);
      String res="same"; int when=-1;
      for (int f=0; f<1500; f++) {
        if (f%40==0) { int k=keys[r.nextInt(keys.length)]; a.keyDown(k); b.keyDown(k);} 
        if (f%40==8) { a.releaseAllKeys(); b.releaseAllKeys(); }
        a.runFrame(au); b.runFrame(au);
        if ((f==200||f==600||f==1499) && hash(a)!=hash(b)) { res="DIFF"; when=f; break; }
      }
      if (res.equals("same")) ok++; else { diff++; bad.add(name+"@"+when); shot(a,b,outDir+"/"+name+".png"); }
      System.out.printf("%-12s %s%n", name, res+(when>=0?" at frame "+when:""));
    }
    System.out.println("same "+ok+", different "+diff+": "+bad);
  }
}
