#!/usr/bin/env python3
"""Tiny Intel 8080 disassembler: dis80.py file.rom base start end"""
import sys
R=['B','C','D','E','H','L','M','A']; RP=['B','D','H','SP']; RP2=['B','D','H','PSW']
CC=['NZ','Z','NC','C','PO','PE','P','M']
ALU=['ADD','ADC','SUB','SBB','ANA','XRA','ORA','CMP']; ALUI=['ADI','ACI','SUI','SBI','ANI','XRI','ORI','CPI']
def dis(m,pc):
    op=m(pc); b1=m(pc+1); w=m(pc+1)|(m(pc+2)<<8)
    x,y,z=op>>6,(op>>3)&7,op&7
    if x==1: return ('HLT' if op==0x76 else f'MOV {R[y]},{R[z]}'),1
    if x==2: return f'{ALU[y]} {R[z]}',1
    if x==0:
        if z==0: return 'NOP',1
        if z==1: return (f'LXI {RP[y>>1]},{w:04X}',3) if y%2==0 else (f'DAD {RP[y>>1]}',1)
        if z==2:
            t=[('STAX B',1),('LDAX B',1),('STAX D',1),('LDAX D',1),(f'SHLD {w:04X}',3),(f'LHLD {w:04X}',3),(f'STA {w:04X}',3),(f'LDA {w:04X}',3)]
            return t[y]
        if z==3: return (f'INX {RP[y>>1]}' if y%2==0 else f'DCX {RP[y>>1]}'),1
        if z==4: return f'INR {R[y]}',1
        if z==5: return f'DCR {R[y]}',1
        if z==6: return f'MVI {R[y]},{b1:02X}',2
        return ['RLC','RRC','RAL','RAR','DAA','CMA','STC','CMC'][y],1
    if z==0: return f'R{CC[y]}',1
    if z==1:
        if y%2==0: return f'POP {RP2[y>>1]}',1
        return ['RET','RET','PCHL','SPHL'][y>>1],1
    if z==2: return f'J{CC[y]} {w:04X}',3
    if z==3:
        t={0:(f'JMP {w:04X}',3),1:(f'JMP {w:04X}',3),2:(f'OUT {b1:02X}',2),3:(f'IN {b1:02X}',2),4:('XTHL',1),5:('XCHG',1),6:('DI',1),7:('EI',1)}
        return t[y]
    if z==4: return f'C{CC[y]} {w:04X}',3
    if z==5:
        if y%2==0: return f'PUSH {RP2[y>>1]}',1
        return f'CALL {w:04X}',3
    if z==6: return f'{ALUI[y]} {b1:02X}',2
    return f'RST {y}',1
if __name__=='__main__':
    data=open(sys.argv[1],'rb').read(); base=int(sys.argv[2],16)
    a=int(sys.argv[3],16); end=int(sys.argv[4],16)
    m=lambda p: data[p-base] if 0<=p-base<len(data) else 0
    while a<end:
        s,n=dis(m,a)
        print(f'{a:04X}  {" ".join(f"{m(a+i):02X}" for i in range(n)):<9} {s}')
        a+=n
