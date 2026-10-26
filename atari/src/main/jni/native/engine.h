#ifndef ATARI_ENGINE_H
#define ATARI_ENGINE_H
#include <stdint.h>
#include <stddef.h>
int engine_open(const char *directory,const char *content,int basic);
void engine_close(void);
int engine_frame(uint32_t *pixels,int capacity,int16_t *audio,int audio_capacity,unsigned buttons,const uint8_t *keys);
int engine_width(void);
int engine_height(void);
double engine_fps(void);
void engine_reset(void);
size_t engine_state_size(void);
int engine_save(void *data,size_t size);
int engine_load(const void *data,size_t size);
uint8_t *engine_memory(void);
#endif
