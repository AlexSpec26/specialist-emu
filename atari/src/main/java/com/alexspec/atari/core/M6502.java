package com.alexspec.atari.core;

/**
 * NMOS 6502 (Atari's 6502C/SALLY instruction set), at instruction boundaries.
 * All 151 documented opcodes, NMOS decimal flags, page/branch timing and IRQ/NMI.
 * Not a per-clock DMA bus implementation. Undocumented opcodes stop explicitly.
 */
public final class M6502 {
    public static final int C=1, Z=2, I=4, D=8, B=16, U=32, V=64, N=128;
    public int a, x, y, sp=0xfd, pc, p=U|I;
    public long cycles, instructions;
    private final CpuBus bus;
    private boolean irq, nmi, irqAllowed;
    private boolean crossed;

    public M6502(CpuBus bus) { this.bus = bus; }

    public void reset() {
        a=x=y=0; sp=0xfd; p=U|I;
        pc=word(0xfffc); cycles=7; instructions=0;
        irq=nmi=irqAllowed=false;
    }

    public void setIrq(boolean level) { irq=level; }
    public void pulseNmi() { nmi=true; }

    private int read(int address) { return bus.read(address & 0xffff) & 255; }
    private void write(int address,int value) { bus.write(address & 0xffff,value & 255); }
    private int next() { int v=read(pc); pc=(pc+1)&0xffff; return v; }
    private int address() { int lo=next(); return lo | next()<<8; }
    private int word(int address) { return read(address) | read(address+1)<<8; }
    private int zpWord(int address) { return read(address&255) | read((address+1)&255)<<8; }
    private void push(int v) { write(0x100|sp,v); sp=(sp-1)&255; }
    private int pop() { sp=(sp+1)&255; return read(0x100|sp); }
    private void flag(int f,boolean on) { p=on ? p|f : p&~f; }
    private int nz(int v) { v&=255; flag(Z,v==0); flag(N,(v&128)!=0); return v; }
    private void compare(int left,int right) { flag(C,left>=right); nz(left-right); }
    private int indexed(int base,int index,boolean store) {
        int addr=(base+index)&0xffff;
        crossed=(base&0xff00)!=(addr&0xff00);
        if (store || crossed) read((base&0xff00)|(addr&255));
        return addr;
    }
    private int zpIndex(int index) { int base=next(); read(base); return (base+index)&255; }

    private void adc(int value) {
        int carry=p&C, sum=a+value+carry;
        flag(Z,(sum&255)==0);
        if ((p&D)!=0) {
            int low=(a&15)+(value&15)+carry;
            if (low>9) low+=6;
            int high=(a&0xf0)+(value&0xf0)+(low>15 ? 16 : 0);
            flag(N,(high&128)!=0);
            flag(V,(~(a^value)&(a^high)&128)!=0);
            if (high>0x90) high+=0x60;
            flag(C,high>255);
            a=(high|(low&15))&255;
        } else {
            flag(V,(~(a^value)&(a^sum)&128)!=0);
            flag(C,sum>255); a=nz(sum);
        }
    }

    private void sbc(int value) {
        int borrow=(p&C)==0 ? 1 : 0;
        int diff=a-value-borrow;
        flag(V,((a^value)&(a^diff)&128)!=0);
        flag(C,diff>=0); nz(diff);
        if ((p&D)!=0) {
            int low=(a&15)-(value&15)-borrow;
            int high=(a>>4)-(value>>4);
            if (low<0) { low-=6; high--; }
            if (high<0) high-=6;
            a=((high<<4)&0xf0)|(low&15);
        } else a=diff&255;
    }

    private int shift(int kind,int value) {
        int carry=p&C;
        switch (kind) {
            case 0: flag(C,(value&128)!=0); return nz(value<<1);
            case 1: flag(C,(value&128)!=0); return nz((value<<1)|carry);
            case 2: flag(C,(value&1)!=0); return nz(value>>1);
            default: flag(C,(value&1)!=0); return nz((value>>1)|(carry<<7));
        }
    }

    private int implied() { read(pc); return 2; }

    private int branch(boolean take) {
        int offset=(byte)next();
        if (!take) return 2;
        read(pc);
        int dest=(pc+offset)&0xffff;
        boolean cross=(pc&0xff00)!=(dest&0xff00);
        if (cross) read((pc&0xff00)|(dest&255));
        pc=dest;
        return cross ? 4 : 3;
    }

    private void interrupt(int vector) {
        read(pc); read(pc);
        push(pc>>8); push(pc); push((p|U)&~B);
        p|=I; irqAllowed=false; pc=word(vector);
    }

    /** Runs one instruction, or one pending interrupt sequence, returning CPU clocks. */
    public int step() {
        if (nmi || (irq && irqAllowed)) {
            int vector=nmi ? 0xfffa : 0xfffe;
            nmi=false; interrupt(vector); cycles+=7; return 7;
        }
        int start=pc, op=next(), oldP=p;
        crossed=false;
        int clocks;
        if ((op&3)==1 && op!=0x89) clocks=alu(op);
        else if ((op&3)==2 && validGroup2(op)) clocks=group2(op);
        else switch (op) {
            case 0x00: next(); push(pc>>8); push(pc); push(p|B|U); p|=I; pc=word(0xfffe); clocks=7; break;
            case 0x08: read(pc); push(p|B|U); clocks=3; break;
            case 0x28: read(pc); read(0x100|sp); p=(pop()|U)&~B; clocks=4; break;
            case 0x48: read(pc); push(a); clocks=3; break;
            case 0x68: read(pc); read(0x100|sp); a=nz(pop()); clocks=4; break;
            case 0x20: {
                int lo=next(); read(0x100|sp); push(pc>>8); push(pc);
                pc=lo|next()<<8; clocks=6; break;
            }
            case 0x40: read(pc); read(0x100|sp); p=(pop()|U)&~B; pc=pop()|pop()<<8; clocks=6; break;
            case 0x60: read(pc); read(0x100|sp); pc=pop()|pop()<<8; read(pc); pc=(pc+1)&0xffff; clocks=6; break;
            case 0x4c: pc=address(); clocks=3; break;
            case 0x6c: { int ptr=address(); pc=read(ptr)|read((ptr&0xff00)|((ptr+1)&255))<<8; clocks=5; break; }
            case 0x10: clocks=branch((p&N)==0); break;
            case 0x30: clocks=branch((p&N)!=0); break;
            case 0x50: clocks=branch((p&V)==0); break;
            case 0x70: clocks=branch((p&V)!=0); break;
            case 0x90: clocks=branch((p&C)==0); break;
            case 0xb0: clocks=branch((p&C)!=0); break;
            case 0xd0: clocks=branch((p&Z)==0); break;
            case 0xf0: clocks=branch((p&Z)!=0); break;
            case 0x18: p&=~C; clocks=implied(); break;
            case 0x38: p|=C; clocks=implied(); break;
            case 0x58: p&=~I; clocks=implied(); break;
            case 0x78: p|=I; clocks=implied(); break;
            case 0xb8: p&=~V; clocks=implied(); break;
            case 0xd8: p&=~D; clocks=implied(); break;
            case 0xf8: p|=D; clocks=implied(); break;
            case 0x88: y=nz(y-1); clocks=implied(); break;
            case 0xc8: y=nz(y+1); clocks=implied(); break;
            case 0xca: x=nz(x-1); clocks=implied(); break;
            case 0xe8: x=nz(x+1); clocks=implied(); break;
            case 0x8a: a=nz(x); clocks=implied(); break;
            case 0x98: a=nz(y); clocks=implied(); break;
            case 0xa8: y=nz(a); clocks=implied(); break;
            case 0xaa: x=nz(a); clocks=implied(); break;
            case 0x9a: sp=x; clocks=implied(); break;
            case 0xba: x=nz(sp); clocks=implied(); break;
            case 0xea: clocks=implied(); break;
            case 0x24: case 0x2c: {
                int value=read(op==0x24 ? next() : address());
                flag(Z,(a&value)==0); p=(p&~(N|V))|(value&(N|V)); clocks=op==0x24 ? 3 : 4; break;
            }
            case 0x84: write(next(),y); clocks=3; break;
            case 0x94: write(zpIndex(x),y); clocks=4; break;
            case 0x8c: write(address(),y); clocks=4; break;
            case 0xa0: y=nz(next()); clocks=2; break;
            case 0xa4: y=nz(read(next())); clocks=3; break;
            case 0xb4: y=nz(read(zpIndex(x))); clocks=4; break;
            case 0xac: y=nz(read(address())); clocks=4; break;
            case 0xbc: y=nz(read(indexed(address(),x,false))); clocks=4+(crossed?1:0); break;
            case 0xc0: compare(y,next()); clocks=2; break;
            case 0xc4: compare(y,read(next())); clocks=3; break;
            case 0xcc: compare(y,read(address())); clocks=4; break;
            case 0xe0: compare(x,next()); clocks=2; break;
            case 0xe4: compare(x,read(next())); clocks=3; break;
            case 0xec: compare(x,read(address())); clocks=4; break;
            default:
                pc=start;
                throw new IllegalStateException(String.format(java.util.Locale.US,
                        "Undocumented opcode $%02X at $%04X (not implemented)",op,start));
        }
        // CLI/SEI/PLP sample the old interrupt mask on NMOS; RTI samples the restored mask.
        irqAllowed=((op==0x58 || op==0x78 || op==0x28 ? oldP : p)&I)==0;
        p=(p|U)&~B;
        cycles+=clocks; instructions++;
        return clocks;
    }

    private int alu(int op) {
        int kind=op>>5, mode=(op>>2)&7, addr=0, value=0, clocks;
        boolean store=kind==4;
        switch (mode) {
            case 0: addr=zpWord(zpIndex(x)); clocks=6; break;
            case 1: addr=next(); clocks=3; break;
            case 2: value=next(); clocks=2; break;
            case 3: addr=address(); clocks=4; break;
            case 4: addr=indexed(zpWord(next()),y,store); clocks=store ? 6 : 5+(crossed?1:0); break;
            case 5: addr=zpIndex(x); clocks=4; break;
            case 6: addr=indexed(address(),y,store); clocks=store ? 5 : 4+(crossed?1:0); break;
            default: addr=indexed(address(),x,store); clocks=store ? 5 : 4+(crossed?1:0); break;
        }
        if (store) { write(addr,a); return clocks; }
        if (mode!=2) value=read(addr);
        switch (kind) {
            case 0: a=nz(a|value); break;
            case 1: a=nz(a&value); break;
            case 2: a=nz(a^value); break;
            case 3: adc(value); break;
            case 5: a=nz(value); break;
            case 6: compare(a,value); break;
            case 7: sbc(value); break;
            default: throw new AssertionError();
        }
        return clocks;
    }

    private boolean validGroup2(int op) {
        int kind=op>>5, mode=(op>>2)&7;
        return mode==1 || mode==3 || mode==5 || (mode==0 && kind==5)
                || (mode==2 && kind<4) || (mode==7 && kind!=4);
    }

    private int group2(int op) {
        int kind=op>>5, mode=(op>>2)&7;
        if (mode==0) { x=nz(next()); return 2; }
        if (mode==2) { read(pc); a=shift(kind,a); return 2; }
        int addr, clocks;
        boolean rmw=kind<4 || kind>=6;
        switch (mode) {
            case 1: addr=next(); clocks=rmw?5:3; break;
            case 3: addr=address(); clocks=rmw?6:4; break;
            case 5: addr=zpIndex(kind==4 || kind==5 ? y : x); clocks=rmw?6:4; break;
            default: addr=indexed(address(),kind==5 ? y : x,rmw); clocks=rmw?7:4+(crossed?1:0); break;
        }
        if (kind==4) write(addr,x);
        else if (kind==5) x=nz(read(addr));
        else {
            int value=read(addr); write(addr,value); // NMOS read/modify/write writes the old byte too
            write(addr,kind<4 ? shift(kind,value) : nz(value+(kind==6 ? -1 : 1)));
        }
        return clocks;
    }
}
