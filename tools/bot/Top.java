import com.alexspec.specialist.core.*; import java.nio.file.*;
public class Top { public static void main(String[] a) throws Exception { Specialist m=new Specialist(); m.loadRom(Files.readAllBytes(Paths.get(a[0]))); m.hardReset(); m.loadState(Files.readAllBytes(Paths.get(a[1])));
  for(int y=0;y<20;y++){ StringBuilder sb=new StringBuilder(); for(int c=0;c<48;c++){ int v=m.peek(0x9000+c*256+y); for(int b=7;b>=0;b--) sb.append(((v>>b)&1)!=0?'#':'.'); } System.out.println(String.format("%2d ",y)+sb.substring(0,240)); } } }
