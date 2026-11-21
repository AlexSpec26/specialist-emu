import com.alexspec.specialist.core.*; import java.nio.file.*;
public class Dump { public static void main(String[] a) throws Exception { Specialist m=new Specialist(); m.loadRom(Files.readAllBytes(Paths.get(a[0]))); m.hardReset(); m.loadState(Files.readAllBytes(Paths.get(a[1]))); byte[] r=new byte[0x9000]; for(int i=0;i<r.length;i++) r[i]=(byte)m.peek(i); Files.write(Paths.get(a[2]), r); } }
