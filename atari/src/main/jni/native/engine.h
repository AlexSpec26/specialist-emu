/*
 * Minimal frontend for the Atari800 libretro core: one global machine driven frame by frame.
 * Used by the Android JNI bridge (jni.c) and by the desktop test (tools/AtariNativeTest.c).
 * GPL-2.0-or-later, see atari/COPYING.
 */
#ifndef ATARI_ENGINE_H
#define ATARI_ENGINE_H
#include <stdint.h>
#include <stddef.h>

/* Largest picture the core produces (400x300) and number of key codes (RETROK_*). */
#define ENGINE_MAX_PIXELS (400 * 300)
#define ENGINE_KEYS 512

/* Starts a fresh machine. directory: folder with optional ATARIXL.ROM / ATARIBAS.ROM
 * (and scratch files of the core); content: program/disk/cartridge, "" or NULL = BASIC;
 * basic: enable the built-in BASIC. Returns 1 on success. */
int engine_open(const char *directory, const char *content, int basic);
void engine_close(void);

/* Runs one frame. pixels: ARGB, at least width*height; audio: interleaved stereo 16-bit;
 * buttons: libretro joypad bits; keys: ENGINE_KEYS bytes (1 = held) or NULL.
 * Returns the number of audio samples written, or -1 if no machine is running. */
int engine_frame(uint32_t *pixels, int capacity, int16_t *audio, int audio_capacity,
                 unsigned buttons, const uint8_t *keys);
int engine_width(void);
int engine_height(void);
double engine_fps(void);

/* Cold start: reloads the current program, or reboots into BASIC. */
void engine_reset(void);

size_t engine_state_size(void);
int engine_save(void *data, size_t size);
int engine_load(const void *data, size_t size);

/* The core's 64 KB memory array, or NULL. */
uint8_t *engine_memory(void);
#endif
