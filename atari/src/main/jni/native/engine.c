/* Android/headless frontend for Atari800. GPL-2.0-or-later; see atari/COPYING. */
#include "engine.h"
#include "libretro.h"
#include "atari.h"
#include "afile.h"
#include "sound.h"
#include "sio.h"
#include "cartridge.h"
#include "cassette.h"
#include "binload.h"
#include "sysrom.h"
#include <stdio.h>
#include <string.h>
#include <stdarg.h>
#include <stdlib.h>
#include <unistd.h>

static char system_dir[400],game_path[500];
static int loaded,initialized,basic_enabled,w=336,h=240;
static unsigned buttons;
static uint8_t keys[512];
static uint32_t picture[400*300];
static int16_t sound[8192];
static int sound_count;
static double fps=49.8607597;
static struct {char key[128],value[256];} options[128];
static int option_count;
extern char RPATH[512];
extern int pauseg,SHOWKEY,PARAMCOUNT;

static void logger(enum retro_log_level level,const char *format,...) {
    (void)level;
    va_list ap;va_start(ap,format);vfprintf(stderr,format,ap);va_end(ap);
}
static bool environment(unsigned cmd,void *data) {
    switch(cmd) {
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        case RETRO_ENVIRONMENT_GET_CONTENT_DIRECTORY:
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *(const char**)data=system_dir;return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: ((struct retro_log_callback*)data)->log=logger;return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: return *(enum retro_pixel_format*)data==RETRO_PIXEL_FORMAT_RGB565;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION: *(unsigned*)data=0;return true;
        case RETRO_ENVIRONMENT_SET_VARIABLES: {
            const struct retro_variable *v=data;option_count=0;
            for(;v && v->key && option_count<128;v++) {
                const char *value=strchr(v->value,';');if(!value)continue;value++;while(*value==' ')value++;
                size_t len=strcspn(value,"|");if(len>255)len=255;
                snprintf(options[option_count].key,128,"%s",v->key);
                memcpy(options[option_count].value,value,len);options[option_count].value[len]=0;option_count++;
            }return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            struct retro_variable *v=data;v->value=NULL;
            if(!strcmp(v->key,"atari800_system"))v->value="800XL (64K)";
            else if(!strcmp(v->key,"atari800_ntscpal"))v->value="PAL";
            else if(!strcmp(v->key,"atari800_resolution"))v->value="336x240";
            else if(!strcmp(v->key,"atari800_internalbasic"))v->value=basic_enabled?"enabled":"disabled";
            else if(!strcmp(v->key,"atari800_keyboard"))v->value="poll";
            else if(!strcmp(v->key,"atari800_vkbd_enabled"))v->value="disabled";
            else if(!strcmp(v->key,"atari800_sioaccel"))v->value="enabled";
            else if(!strcmp(v->key,"atari800_opt2"))v->value="disabled";
            else if(!strcmp(v->key,"paddle_active"))v->value="disabled";
            else if(!strcmp(v->key,"atari800_cfg"))v->value="disabled";
            else if(!strcmp(v->key,"atari800_pokey_stereo"))v->value="disabled";
            else for(int i=0;i<option_count;i++)if(!strcmp(v->key,options[i].key)){v->value=options[i].value;break;}
            return v->value!=NULL;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: *(bool*)data=false;return true;
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: fps=((struct retro_system_av_info*)data)->timing.fps;return true;
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK:
        case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE:
        case RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE:
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
        case RETRO_ENVIRONMENT_SET_MESSAGE: return true;
        default:return false;
    }
}
static void video(const void *data,unsigned width,unsigned height,size_t pitch) {
    if(!data || width>400 || height>300)return;
    w=(int)width;h=(int)height;
    for(unsigned y=0;y<height;y++) {
        const uint16_t *row=(const uint16_t*)((const uint8_t*)data+y*pitch);
        for(unsigned x=0;x<width;x++) {
            unsigned c=row[x],r=(c>>11)&31,g=(c>>5)&63,b=c&31;
            picture[y*width+x]=0xff000000|((r*255/31)<<16)|((g*255/63)<<8)|(b*255/31);
        }
    }
}
static size_t audio_batch(const int16_t *data,size_t frames) {
    size_t count=frames*2;if(count>8192-(size_t)sound_count)count=8192-sound_count;
    memcpy(sound+sound_count,data,count*sizeof(int16_t));sound_count+=(int)count;return frames;
}
static void audio_sample(int16_t l,int16_t r) {int16_t a[2]={l,r};audio_batch(a,1);}
static void poll(void) {}
static int16_t input(unsigned port,unsigned device,unsigned index,unsigned id) {
    (void)index;if(port>0)return 0;device&=RETRO_DEVICE_MASK;
    if(device==RETRO_DEVICE_JOYPAD && id<16)return (buttons>>id)&1;
    if(device==RETRO_DEVICE_KEYBOARD && id<512)return keys[id];
    return 0;
}
void engine_close(void) {
    if(loaded){
        /* Keep process-lifetime device/ESC infrastructure alive across reloads.
         * Atari800_Exit removes hooks which are not reinstalled by libretro init. */
        for(int disk=1;disk<=8;disk++)SIO_Dismount(disk);
        CARTRIDGE_Remove();CASSETTE_Remove();
        if(BINLOAD_bin_file){fclose(BINLOAD_bin_file);BINLOAD_bin_file=NULL;}
        BINLOAD_start_binloading=0;
        retro_unload_game();loaded=0;
    }
    if(initialized){retro_deinit();initialized=0;}
    memset(keys,0,sizeof(keys));buttons=0;
}
int engine_open(const char *directory,const char *content,int basic) {
    if(!directory || strlen(directory)>=sizeof(system_dir)-2 || (content && strlen(content)>=sizeof(game_path)))return 0;
    if(content && *content && AFILE_DetectFileType(content)==AFILE_ERROR)return 0;
    engine_close();
    // Drop paths to firmware removed/replaced through the Android settings.
    for(int i=0;i<SYSROM_LOADABLE_SIZE;i++)if(SYSROM_roms[i].filename && access(SYSROM_roms[i].filename,R_OK)!=0)SYSROM_roms[i].filename[0]=0;
    snprintf(system_dir,sizeof(system_dir),"%s",directory);
    snprintf(game_path,sizeof(game_path),"%s",content?content:"");basic_enabled=basic;
    memset(picture,0,sizeof(picture));sound_count=0;option_count=0;PARAMCOUNT=0;
    retro_set_environment(environment);retro_set_video_refresh(video);retro_set_audio_sample(audio_sample);
    retro_set_audio_sample_batch(audio_batch);retro_set_input_poll(poll);retro_set_input_state(input);
    Sound_enabled=1; /* Sound_Exit disables it; each new machine must recreate its buffers. */
    retro_init();initialized=1;
    snprintf(RPATH,512,"%s",*game_path?game_path:"prg");
    struct retro_game_info game={game_path,NULL,0,NULL};
    if(!retro_load_game(*game_path?&game:NULL)){engine_close();return 0;}
    loaded=1;pauseg=0;SHOWKEY=-1;
    struct retro_system_av_info info;retro_get_system_av_info(&info);fps=info.timing.fps;
    /* Recreate the callback ring after reload (upstream Exit releases it). */
    if(!Sound_Setup()){engine_close();return 0;}
    Sound_Continue();
    if(*game_path && AFILE_OpenFile(game_path,1,1,0)==AFILE_ERROR){engine_close();return 0;}
    return 1;
}
int engine_frame(uint32_t *pixels,int capacity,int16_t *audio,int audio_capacity,unsigned mask,const uint8_t *held) {
    if(!loaded)return -1;
    buttons=mask;if(held)memcpy(keys,held,512);else memset(keys,0,512);
    sound_count=0;retro_run();
    if(pixels && capacity>=w*h)memcpy(pixels,picture,w*h*sizeof(uint32_t));
    int count=sound_count<audio_capacity?sound_count:audio_capacity;
    if(audio && count>0)memcpy(audio,sound,count*sizeof(int16_t));return count;
}
int engine_width(void){return w;}int engine_height(void){return h;}double engine_fps(void){return fps;}
void engine_reset(void){if(loaded){if(*game_path)AFILE_OpenFile(game_path,1,1,0);else Atari800_Coldstart();memset(keys,0,512);buttons=0;}}
size_t engine_state_size(void){return loaded?retro_serialize_size():0;}
int engine_save(void *data,size_t size){return loaded && retro_serialize(data,size);}
int engine_load(const void *data,size_t size){return loaded && retro_unserialize(data,size);}
uint8_t *engine_memory(void){return loaded?retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM):NULL;}
