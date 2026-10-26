import com.alexspec.atari.core.*;
import java.nio.file.*;
import java.util.*;

/** Desktop tests: javac -d /tmp/atari atari/src/main/java/com/alexspec/atari/core/*.java tools/AtariCoreTest.java
 * java -cp /tmp/atari AtariCoreTest [path/to/Klaus/6502_functional_test.bin]
 * External test ROM: https://github.com/Klaus2m5/6502_65C02_functional_tests (not bundled).
 */
public final class AtariCoreTest {
    static final class Memory implements CpuBus {
        final byte[] data=new byte[65536];
        final List<String> writes=new ArrayList<>();
        public int read(int a) { return data[a]&255; }
        public void write(int a,int v) { data[a]=(byte)v; writes.add(String.format("%04X:%02X",a,v)); }
        void put(int a,int... values) { for (int v:values) data[a++&65535]=(byte)v; }
    }
    static void eq(long expected,long got,String label) {
        if (expected!=got) throw new AssertionError(label+": expected "+expected+", got "+got);
    }
    static void yes(boolean value,String label) { if(!value) throw new AssertionError(label); }
    static M6502 cpu(Memory m,int pc) { M6502 c=new M6502(m);c.pc=pc;return c; }

    static void binary() {
        Memory m=new Memory();M6502 c=cpu(m,0);
        for (int op:new int[]{0x69,0xe9}) for(int a=0;a<256;a++) for(int b=0;b<256;b++) for(int carry=0;carry<2;carry++) {
            m.put(0,op,b);c.pc=0;c.a=a;c.p=M6502.U|M6502.I|carry;
            eq(2,c.step(),"immediate timing");
            int total=op==0x69 ? a+b+carry : a-b-(1-carry), result=total&255;
            eq(result,c.a,"binary result");
            eq(op==0x69 ? (total>255 ? 1:0) : (total>=0 ? 1:0),c.p&M6502.C,"carry");
            eq(result==0 ? M6502.Z:0,c.p&M6502.Z,"zero");
            eq(result&M6502.N,c.p&M6502.N,"negative");
            int overflow=op==0x69 ? (~(a^b)&(a^result)&128) : ((a^b)&(a^result)&128);
            eq(overflow!=0 ? M6502.V:0,c.p&M6502.V,"overflow");
        }
        System.out.println("BINARY ADC/SBC OK (262144 cases)");
    }
    static int bcd(int n) { return (n/10)*16+n%10; }
    static void decimal() {
        Memory m=new Memory(); M6502 c=cpu(m,0);
        for(int op:new int[]{0x69,0xe9}) for(int a=0;a<100;a++) for(int b=0;b<100;b++) for(int carry=0;carry<2;carry++) {
            m.put(0,op,bcd(b)); c.pc=0;c.a=bcd(a);c.p=M6502.U|M6502.I|M6502.D|carry;
            c.step(); int total=op==0x69 ? a+b+carry : a-b-(1-carry);
            eq(bcd((total+100)%100),c.a,"decimal result");
            eq(op==0x69 ? (total>=100?1:0):(total>=0?1:0),c.p&M6502.C,"decimal carry");
        }
        // NMOS flags are not derived from the final BCD result (unlike 65C02).
        m.put(0,0x69,0x50); c.pc=0;c.a=0x50;c.p=M6502.U|M6502.D;c.step();
        eq(0,c.a,"BCD 50+50");yes((c.p&M6502.N)!=0,"NMOS decimal N");yes((c.p&M6502.Z)==0,"NMOS decimal Z");
        System.out.println("DECIMAL ADC/SBC OK (40000 valid BCD cases + NMOS flags)");
    }
    static void timingAndBus() {
        Memory m=new Memory();M6502 c=cpu(m,0x200);
        m.put(0x200,0xbd,0xff,0x20,0x9d,0x00,0x20,0x1e,0x00,0x20);c.x=1;
        eq(5,c.step(),"LDA abs X crosses page");eq(5,c.step(),"STA abs X fixed timing");
        m.writes.clear();eq(7,c.step(),"ASL abs X fixed timing");eq(2,m.writes.size(),"RMW old/new writes");
        m.put(0x200,0xbd,0x00,0x20);c.pc=0x200;eq(4,c.step(),"LDA no page cross");
        m.put(0x2fd,0xd0,2);c.pc=0x2fd;c.p=M6502.U;eq(4,c.step(),"branch cross");eq(0x301,c.pc,"branch target");
        c.pc=0x2fd;c.p=M6502.U|M6502.Z;eq(2,c.step(),"untaken branch");
        m.put(0x300,0xd0,2);c.pc=0x300;c.p=M6502.U;eq(3,c.step(),"taken same page");
        m.put(0x200,0xb1,0xff);m.put(0xff,0xff);m.put(0,0x20);m.put(0x100,0x99);c.pc=0x200;c.y=1;
        eq(6,c.step(),"indirect Y cross and zero-page wrap");eq(0x202,c.pc,"pc");
        m.put(0x200,0x6c,0xff,0x20);m.put(0x20ff,0x34);m.put(0x2000,0x12);m.put(0x2100,0x99);c.pc=0x200;
        eq(5,c.step(),"JMP indirect");eq(0x1234,c.pc,"NMOS JMP page wrap");
        m.put(0xffff,0xa9);m.put(0,0x77);c.pc=0xffff;c.step();eq(1,c.pc,"PC wraps");eq(0x77,c.a,"wrapped operand");
        m.put(0x200,0x02);c.pc=0x200;try { c.step();throw new AssertionError("illegal opcode accepted"); }
        catch(IllegalStateException expected) { eq(0x200,c.pc,"illegal stops at instruction"); }
        System.out.println("TIMING / PAGE WRAP / RMW / UNSUPPORTED OPCODE OK");
    }
    static void interrupts() {
        Memory m=new Memory();m.put(0xfffc,0x00,0x20);m.put(0xfffe,0x00,0x30);m.put(0xfffa,0x00,0x40);
        m.put(0x2000,0x58,0xea,0xea);m.put(0x3000,0x40);m.put(0x4000,0x40);
        M6502 c=new M6502(m);c.reset();eq(0x2000,c.pc,"reset vector");eq(7,c.cycles,"reset clocks");
        c.setIrq(true);c.step();eq(0x2001,c.pc,"CLI IRQ delay");c.step();eq(0x2002,c.pc,"one instruction after CLI");
        eq(7,c.step(),"IRQ clocks");eq(0x3000,c.pc,"IRQ vector");eq(0xfa,c.sp,"IRQ pushes 3 bytes");
        eq(0,m.read(0x1fb)&M6502.B,"hardware IRQ B clear");c.setIrq(false);eq(6,c.step(),"RTI timing");eq(0x2002,c.pc,"RTI target");
        c.p|=M6502.I;c.pulseNmi();eq(7,c.step(),"NMI despite I");eq(0x4000,c.pc,"NMI vector");c.step();
        m.put(0x2002,0,0xea);c.pc=0x2002;c.p=M6502.U|M6502.D;eq(7,c.step(),"BRK timing");
        yes((m.read(0x1fb)&M6502.B)!=0,"BRK pushes B");yes((c.p&M6502.D)!=0,"NMOS IRQ preserves D");
        c.step();eq(0x2004,c.pc,"BRK return skips padding");
        // JSR/RTS and SP wrap.
        m.put(0x200,0x20,0x00,0x30);m.put(0x3000,0x60);c.pc=0x200;c.sp=0;
        eq(6,c.step(),"JSR");eq(0xfe,c.sp,"stack wrap");eq(6,c.step(),"RTS");eq(0x203,c.pc,"return address");eq(0,c.sp,"SP restored");
        System.out.println("RESET / IRQ / NMI / BRK / STACK OK");
    }
    static void banking() {
        Atari65XE m=new Atari65XE();byte[] os=new byte[16384], basic=new byte[8192];
        Arrays.fill(os,(byte)0xa5);Arrays.fill(basic,(byte)0xba);os[0x3ffc]=0;os[0x3ffd]=(byte)0xc0;
        m.write(0xc000,0x42);m.loadOs(os);m.loadBasic(basic);m.resetCpu();
        eq(0xc000,m.cpu.pc,"OS reset vector visible by default");eq(0xa5,m.read(0xc000),"OS active high");
        m.write(0xc000,0x66);eq(0x42,m.peekRam(0xc000),"ROM writes inhibited");eq(0,m.read(0xd301),"PIA DDR after reset");
        m.write(0xd301,0xff);m.write(0xd303,4);m.write(0xd301,0xff); // DDR output, then PORT latch
        eq(0xff,m.read(0xd3fd),"PIA mirror");
        m.write(0xd301,0xfe);eq(0x42,m.read(0xc000),"OS bank out");m.write(0xc000,0x77);
        m.write(0xd301,0xfd);eq(0xba,m.read(0xa000),"BASIC active low");
        m.write(0xa000,0x12);eq(0,m.peekRam(0xa000),"BASIC write protected");
        m.write(0xd301,0x7f);eq(0xa5,m.read(0x5000),"self test overlay");m.write(0x5000,0x99);eq(0,m.peekRam(0x5000),"self test write protected");
        m.write(0xd301,0xff);eq(0,m.read(0x5000),"self test bank out");
        m.write(0xd301,0xfe);eq(0x77,m.read(0xc000),"hidden RAM preserved");
        m.write(0xd303,0);m.write(0xd301,0);eq(0xff,m.portb(),"DDR input pulls high");
        os[0]=(byte)0xa9;os[1]=0x42;os[2]=(byte)0x8d;os[3]=0;os[4]=0x10;
        m.loadOs(os);m.reset();m.stepInstruction();m.stepInstruction();
        eq(0x42,m.read(0x1000),"6502 runs through the Atari memory bus");
        try {m.loadOs(new byte[1]);throw new AssertionError("bad ROM accepted");}
        catch(IllegalArgumentException expected) {eq(0xa9,m.read(0xc000),"bad ROM leaves loaded image intact");}
        System.out.println("65XE OS / BASIC / SELF TEST / PIA DDR BANKING + CPU BUS OK");
    }
    static void klaus(String file) throws Exception {
        Memory m=new Memory();byte[] data=Files.readAllBytes(Path.of(file));eq(65536,data.length,"Klaus image size");
        System.arraycopy(data,0,m.data,0,data.length);M6502 c=cpu(m,0x400);c.p=M6502.U;
        // Avoid retaining millions of test writes in this long-running conformance test.
        M6502 k=new M6502(new CpuBus(){public int read(int a){return m.data[a]&255;}public void write(int a,int v){m.data[a]=(byte)v;}});
        k.pc=c.pc;k.p=c.p;
        for(int i=0;i<100_000_000;i++) {
            int pc=k.pc;k.step();if(pc==k.pc){eq(0x3469,k.pc,"Klaus success trap");
                System.out.println("KLAUS 6502 OK: "+(i+1)+" instructions, "+k.cycles+" cycles");return;}
        }
        throw new AssertionError("Klaus timeout");
    }
    public static void main(String[] args) throws Exception {
        binary();decimal();timingAndBus();interrupts();banking();
        if(args.length>0) klaus(args[0]);
        System.out.println("ATARI CORE OK");
    }
}
