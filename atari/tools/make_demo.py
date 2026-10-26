#!/usr/bin/env python3
"""Build our original CC0 Atari joystick demo. No external assembler/ROM/game needed."""
from pathlib import Path
import struct
base=0x2000
code=bytearray(); labels={}; fixes=[]
def emit(*values): code.extend(values)
def label(name): labels[name]=base+len(code)
def absop(op,address): emit(op,address&255,address>>8)
def ref(op,name,relative=False):
    emit(op); fixes.append((len(code),name,relative));emit(0) if relative else emit(0,0)
def branch(op,name): ref(op,name,True)
def lda(v): emit(0xa9,v)
def sta(a): absop(0x8d,a)
def jsr(n): ref(0x20,n)
def jmp(n): ref(0x4c,n)
# OS has already started when XEX RUNAD is dispatched. Take over display/input.
emit(0x78);lda(0);sta(0xd40e);sta(0xd400);sta(0x80);sta(0x85);sta(0x86)
lda(4);sta(0x81);sta(0x82);lda(30);sta(0x83);lda(14);sta(0x84)
lda(0);sta(0xd402);lda(0x30);sta(0xd403);lda(0xe0);sta(0xd409)
lda(0x22);sta(0xd400);lda(0x0e);sta(0xd016);lda(0x0e);sta(0xd017);lda(0x22);sta(0xd018);lda(0x02);sta(0xd01a)
lda(3);sta(0xd20f);lda(60);sta(0xd200);lda(0);sta(0xd201)
label('frame');absop(0xad,0xd40b);emit(0xc9,124);branch(0xd0,'frame')
label('leave');absop(0xad,0xd40b);emit(0xc9,124);branch(0xf0,'leave')
# Eight-frame cadence makes touch controls usable.
absop(0xee,0x86);absop(0xad,0x86);emit(0x29,7);branch(0xd0,'frame')
# Erase player (x,y).
jsr('pointer');lda(0);emit(0xa0,0,0x91,0x90)
# Active-low joystick; constrain inside play area.
for bit,coord,limit,inc in [(1,0x82,3,False),(2,0x82,22,True),(4,0x81,1,False),(8,0x81,38,True)]:
    skip=f'skip{bit}'
    absop(0xad,0xd300);emit(0x29,bit);branch(0xd0,skip)
    absop(0xad,coord);emit(0xc9,limit);branch(0xf0,skip)
    absop(0xee if inc else 0xce,coord);label(skip)
# Capture target.
absop(0xad,0x81);absop(0xcd,0x83);branch(0xd0,'draw')
absop(0xad,0x82);absop(0xcd,0x84);branch(0xd0,'draw')
absop(0xee,0x80);absop(0xad,0x80);emit(0xc9,10);branch(0xd0,'score');lda(0);sta(0x80)
label('score');absop(0xad,0x80);emit(0x18,0x69,0x10);sta(0x402f)
absop(0xad,0xd20a);emit(0x29,31,0x18,0x69,3);sta(0x83)
absop(0xad,0xd20a);emit(0x29,15,0x18,0x69,4);sta(0x84)
lda(3);sta(0x85);lda(0xaa);sta(0xd201)
label('draw')
# Draw player in current position.
jsr('pointer');lda(0x21);emit(0xa0,0,0x91,0x90)
# Draw star at target using row-address table.
absop(0xac,0x84);jsr('row');absop(0xad,0x83);emit(0x18,0x65,0x90,0x85,0x90);branch(0x90,'targetptr');emit(0xe6,0x91)
label('targetptr');lda(0x0a);emit(0xa0,0,0x91,0x90)
# Fire produces an audible test tone too.
absop(0xad,0xd010);emit(0x29,1);branch(0xd0,'nofire');lda(0xa8);sta(0xd201);jmp('frame')
label('nofire');absop(0xad,0x85);branch(0xf0,'mute');absop(0xce,0x85);jmp('frame')
label('mute');lda(0);sta(0xd201);jmp('frame')
label('pointer');absop(0xac,0x82);jsr('row');absop(0xad,0x81);emit(0x18,0x65,0x90,0x85,0x90);branch(0x90,'ptrdone');emit(0xe6,0x91)
label('ptrdone');emit(0x60)
label('row');ref(0xb9,'lo');emit(0x85,0x90);ref(0xb9,'hi');emit(0x85,0x91,0x60)
label('lo');emit(*[(0x4000+y*40)&255 for y in range(24)])
label('hi');emit(*[(0x4000+y*40)>>8 for y in range(24)])
for pos,name,relative in fixes:
    target=labels[name]
    if relative:
        delta=target-(base+pos+1)
        if not -128<=delta<=127: raise ValueError((name,delta))
        code[pos]=delta&255
    else:code[pos:pos+2]=struct.pack('<H',target)
text=bytearray(960)
def line(row,s):
    for i,c in enumerate(s[:40]): text[row*40+i]=(ord(c)-32)&63
line(0,' CATCH THE STAR - ORIGINAL DEMO')
line(1,'SCORE: 0   MOVE: JOYSTICK   FIRE: SOUND')
line(23,' A = YOU    * = TARGET    COLLECT STARS')
# 3 blank rows + 24 text rows + jump-and-wait.
dl=bytes([0x70]*3+[0x42,0,0x40]+[2]*23+[0x41,0,0x30])
def block(addr,data):return struct.pack('<HH',addr,addr+len(data)-1)+data
xex=b'\xff\xff'+block(base,code)+block(0x3000,dl)+block(0x4000,text)+block(0x2e0,struct.pack('<H',base))
path=Path(__file__).resolve().parents[1]/'src/main/assets/demos/catch-the-star.xex'
path.write_bytes(xex)
print(f'{path.name}: {len(xex)} bytes, {len(code)} bytes of code')
