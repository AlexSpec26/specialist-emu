/* Tests the actual playable Atari800 frontend with its freely distributed replacement OS. */
#include "engine.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
static uint32_t pixels[120000];static int16_t audio[8192];static uint8_t held[512];
static long nonzero;
static void frames(int n,unsigned buttons) {
    for(int i=0;i<n;i++) {
        int count=engine_frame(pixels,120000,audio,8192,buttons,held);
        assert(count>0 && count<=8192);
        for(int j=0;j<count;j++)if(audio[j]>200 || audio[j]<-200)nonzero++;
    }
}
static void screenshot(const char *path) {
    FILE *f=fopen(path,"wb");assert(f);int w=engine_width(),h=engine_height();fprintf(f,"P6\n%d %d\n255\n",w,h);
    for(int i=0;i<w*h;i++){unsigned char p[3]={pixels[i]>>16,pixels[i]>>8,pixels[i]};fwrite(p,1,3,f);}fclose(f);
}
int main(int argc,char **argv) {
    assert(argc==2);mkdir("/tmp/atari-native-system",0700);
    assert(engine_open("/tmp/atari-native-system","",1));
    frames(220,0);
    assert(engine_width()==336 && engine_height()==240);
    assert(engine_fps()>49 && engine_fps()<51);
    uint8_t *ram=engine_memory();assert(ram);
    int screen=ram[0x58]|ram[0x59]<<8,ready=0;
    for(int i=0;i<955;i++)if((ram[(screen+i)&65535]&63)==0x32 && (ram[(screen+i+1)&65535]&63)==0x25
        && (ram[(screen+i+2)&65535]&63)==0x21 && (ram[(screen+i+3)&65535]&63)==0x24 && (ram[(screen+i+4)&65535]&63)==0x39)ready=1;
    screenshot("/tmp/atari-basic.ppm");assert(ready);
    // Enter PRINT 123 through the actual keyboard callback path.
    const int text[]={'p','r','i','n','t',' ', '1','2','3',13};
    for(unsigned i=0;i<sizeof(text)/sizeof(*text);i++){held[text[i]]=1;frames(4,0);held[text[i]]=0;frames(4,0);}
    frames(20,0);screenshot("/tmp/atari-typed.ppm");
    int printed=0;for(int i=0;i<958;i++)if((ram[(screen+i)&65535]&127)==0x11 && (ram[(screen+i+1)&65535]&127)==0x12 && (ram[(screen+i+2)&65535]&127)==0x13)printed++;
    assert(printed>=2); // typed command and result
    puts("NATIVE BASIC BOOT + KEYBOARD + VIDEO OK");
    assert(engine_open("/tmp/atari-native-system",argv[1],0));
    frames(400,0);ram=engine_memory();
    assert(ram[0x81]==4 && ram[0x82]==4);
    screenshot("/tmp/atari-demo.ppm");
    frames(16,1u<<7);assert(ram[0x81]>4); // joystick RIGHT
    nonzero=0;frames(16,1u<<0);assert(nonzero>100); // FIRE -> POKEY audio
    int colours=0;uint32_t palette[32];for(int i=0;i<336*240;i++){int j;for(j=0;j<colours;j++)if(palette[j]==pixels[i])break;if(j==colours && colours<32)palette[colours++]=pixels[i];}assert(colours>=3);
    puts("NATIVE XEX + JOYSTICK + POKEY SOUND + ANTIC/GTIA VIDEO OK");
    size_t size=engine_state_size();assert(size>10000 && size<8*1024*1024);void *state=malloc(size);assert(state && engine_save(state,size));
    unsigned char memory[65536];frames(20,1u<<7);memcpy(memory,engine_memory(),65536);
    assert(engine_load(state,size));frames(20,1u<<7);assert(!memcmp(memory,engine_memory(),65536));free(state);
    puts("NATIVE SAVE/RESTORE REPLAY RAM OK");
    for(int i=0;i<5;i++){assert(engine_open("/tmp/atari-native-system",argv[1],0));frames(400,0);}
    // A minimal original boot disk exercises the ATR/SIO path.
    unsigned char disk[16+720*128]={0};disk[0]=0x96;disk[1]=2;disk[2]=0x80;disk[3]=0x16;disk[4]=128;
    unsigned char boot[]={0,1,0,7,6,7,0xa9,0x55,0x8d,0,9,0x18,0x60};memcpy(disk+16,boot,sizeof(boot));
    FILE *f=fopen("/tmp/atari-native-system/test.atr","wb");assert(f);fwrite(disk,1,sizeof(disk),f);fclose(f);
    assert(engine_open("/tmp/atari-native-system","/tmp/atari-native-system/test.atr",0));frames(400,0);assert(engine_memory()[0x900]==0x55);
    puts("NATIVE ATR BOOT + SIO OK");
    // Original standard 8K cartridge with INIT/RUN vectors, no third-party game.
    unsigned char cart[16+8192]={0};memcpy(cart,"CART",4);cart[7]=1;
    unsigned char init[]={0xa9,0x66,0x8d,1,9,0x60};memcpy(cart+16,init,sizeof(init));
    unsigned char run[]={0xa9,0x67,0x8d,2,9,0x4c,0x10,0xa0};memcpy(cart+16+16,run,sizeof(run));
    cart[16+0x1ffa]=0x10;cart[16+0x1ffb]=0xa0;cart[16+0x1ffc]=0;cart[16+0x1ffd]=4;cart[16+0x1ffe]=0;cart[16+0x1fff]=0xa0;
    unsigned sum=0;for(int i=16;i<(int)sizeof(cart);i++)sum+=cart[i];cart[8]=sum>>24;cart[9]=sum>>16;cart[10]=sum>>8;cart[11]=sum;
    f=fopen("/tmp/atari-native-system/test.car","wb");assert(f);fwrite(cart,1,sizeof(cart),f);fclose(f);
    assert(engine_open("/tmp/atari-native-system","/tmp/atari-native-system/test.car",0));frames(250,0);assert(engine_memory()[0x901]==0x66 && engine_memory()[0x902]==0x67);
    puts("NATIVE CAR INIT/RUN OK");
    engine_close();assert(!engine_open("/tmp/atari-native-system","/no/such/file.xex",0));
    puts("NATIVE RELOAD / BAD FILE HANDLING OK");
    puts("ATARI PLAYABLE CORE OK");return 0;
}
