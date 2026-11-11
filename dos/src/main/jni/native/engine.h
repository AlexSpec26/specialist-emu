/*
 * The DOS PC as the app sees it: one global DOSBox Pure machine (libretro core) with a 286-class
 * speed by default, picture, sound, keyboard and touch mouse, saved states and a few seconds of
 * history for rewinding. Used by the JNI bridge (jni.c) and the desktop test
 * (dos/tools/DosNativeTest.c). GPL-2.0-or-later, see dos/COPYING.
 */
#ifndef DOS_ENGINE_H
#define DOS_ENGINE_H
#include <stddef.h>
#include <stdint.h>

/* Largest picture the core produces (SVGA modes); smaller ones use the top-left part. */
#define ENGINE_MAX_W 1280
#define ENGINE_MAX_H 1024

/* Starts a fresh machine. system_dir: the core's system folder; save_dir: where the core keeps
 * files a game writes (its own save games); content: a .zip/.exe/.com/.bat/.img/.iso file, or
 * "" / NULL for the start menu with an empty C: drive. Returns 0 on failure. */
int engine_open(const char *system_dir, const char *save_dir, const char *content);
void engine_close(void);
int engine_is_open(void);

/* A core option (dosbox_pure_*), applied from the next frame on. Options given before
 * engine_open count from the start. */
void engine_set_option(const char *key, const char *value);

/* Runs one video frame. Its sound (interleaved stereo) goes to audio, at most cap samples;
 * returns the number of samples written. */
int engine_frame(int16_t *audio, int cap);
double engine_fps(void);
int engine_sample_rate(void);
long long engine_frames(void);

/* The last picture: size, aspect ratio (width / height as shown), and pixels as ARGB. */
int engine_width(void);
int engine_height(void);
double engine_aspect(void);
/* copies the picture into argb (at least width*height); returns width*height or 0 */
int engine_picture(uint32_t *argb, int cap);

/* Keyboard by libretro key code (RETROK_*), and a touch pad for the mouse: x and y in
 * -32767..32767 across the picture, pressed 0/1 (drag moves, tap clicks, two-finger tap is
 * the right button — handled by the core). */
void engine_key(int retrok, int down);
void engine_release_keys(void);
void engine_pointer(int x, int y, int pressed, int count);

/* Saved state of the whole machine. Loading drops the history. */
size_t engine_state_size(void);
int engine_save(void *data, size_t size);
int engine_load(const void *data, size_t size);

/* History for rewinding: a snapshot every quarter of a second, about 6 seconds kept.
 * engine_rewind goes back at least `seconds` (or to the oldest snapshot) and returns how far
 * it actually went, 0 if there is nothing to go back to. */
double engine_history_seconds(void);
double engine_rewind(double seconds);
void engine_history_clear(void);
#endif
