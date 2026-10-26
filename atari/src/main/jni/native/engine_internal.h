/*
 * What engine.c (the libretro frontend) and timeline.c (time control) give each other.
 * GPL-2.0-or-later, see atari/COPYING.
 */
#ifndef ATARI_ENGINE_INTERNAL_H
#define ATARI_ENGINE_INTERNAL_H
#include "engine.h"

/* engine.c */
int engine_is_open(void);
/* One frame of the core with these controls (no rendering; Screen_atari is updated). */
void engine_frame_body(const EngineInput *in);
/* Where the core's audio of completed frames goes (interleaved stereo); NULL = dropped. */
void engine_set_audio_sink(size_t (*sink)(const int16_t *data, size_t count));
/* Full machine state, including what the core's own save state leaves out. */
size_t engine_snapshot_size(void);
int engine_snapshot_save(void *data, size_t size);
int engine_snapshot_load(const void *data, size_t size);

/* timeline.c */
void timeline_reset(void);
void timeline_free(void);
void timeline_to_frame_start(void);
#endif
