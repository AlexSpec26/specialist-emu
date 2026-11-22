import com.alexspec.specialist.core.*;
import java.nio.file.*;
/** ExplorerRun rom state profile seconds: the app's bot (core/Explorer) on the desktop, live machine driven frame by frame */
public class ExplorerRun {
  public static void main(String[] a) throws Exception {
    byte[] rom=Files.readAllBytes(Paths.get(a[0]));
    Specialist m=new Specialist(); m.loadRom(rom); m.hardReset(); m.loadState(Files.readAllBytes(Paths.get(a[1]))); m.setHistoryEnabled(false);
    Explorer.Profile p=Explorer.Profile.parse(new String(Files.readAllBytes(Paths.get(a[2])), "UTF-8"));
    Explorer bot=new Explorer(rom,p,Runtime.getRuntime().availableProcessors());
    short[] au=new short[4000]; int sec=Integer.parseInt(a[3]); long t0=System.nanoTime();
    for(int f=0;f<sec*50;f++){ bot.beforeFrame(m); m.runFrame(au); }
    bot.stop(m);
    Explorer.View v=bot.view;
    System.out.printf("%s: %d s of play in %.0f s, deaths %d, places %d, last search x%.0f, futures %d%n", p.title, sec, (System.nanoTime()-t0)/1e9, bot.deaths, bot.placesVisited(), v==null?0:v.speedX, v==null?0:v.futures.size());
  }
}
