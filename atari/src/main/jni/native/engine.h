/*
 * The Atari machine as the app sees it: one global Atari 800XL/65XE (PAL, 64 KB) on top of the
 * Atari800 libretro core, with time control (any speed, stops between instructions, steps
 * back) and a memory-access trace. Used by the JNI bridge (jni.c) and the desktop test
 * (tools/AtariNativeTest.c). GPL-2.0-or-later, see atari/COPYING.
 */
#ifndef ATARI_ENGINE_H
#define ATARI_ENGINE_H
#include <stdint.h>
#include <stddef.h>

/* Picture: Screen_atari cropped to 336x240, as on a TV. */
#define ENGINE_WIDTH 336
#define ENGINE_HEIGHT 240
/* Machine cycles per second (PAL); 114 per scan line, 312 lines per frame. */
#define ENGINE_CPU_HZ 1773447
#define ENGINE_LINE_CYCLES 114

/* Controls for one frame. akey: Atari key code (akey.h, with 0x40 = SHIFT, 0x80 = CONTROL)
 * or -1 for none, -5 BREAK, -2 RESET; shift: SHIFT held (also without a key); consol:
 * START/SELECT/OPTION, active-low bits 1/2/4 (7 = none); buttons: joystick 1 as libretro
 * joypad bits (0 fire, 4 up, 5 down, 6 left, 7 right). */
typedef struct {
	int akey, shift, consol, buttons;
} EngineInput;

/* Starts a fresh machine. directory: folder with optional ATARIXL.ROM / ATARIBAS.ROM;
 * content: program/disk/cartridge file, "" or NULL = BASIC; basic: built-in BASIC on. */
int engine_open(const char *directory, const char *content, int basic);
void engine_close(void);
/* The content made the core start an Atari 5200 console instead of the 800XL (a 5200 cartridge,
 * .a52 or a .bin/.rom found in the core's 5200 list). The controls are mapped onto its keypad. */
int engine_is_5200(void);
/* Cold start of the current program (or BASIC). */
void engine_reset(void);
double engine_fps(void);
long long engine_frame_cycles(void);

/* Controls used from the next new frame on (frames replayed from history keep theirs). */
void engine_set_input(const EngineInput *in);

/* Runs forward `cycles` (whole instructions), or to the end of the current frame. Audio of
 * the frames completed meanwhile goes to `audio` (interleaved stereo, 44100 Hz).
 * Returns the number of samples written, -1 if nothing is open. */
int engine_run(long long cycles, int to_frame_end, int16_t *audio, int audio_capacity);
/* Forward by instructions / by cycles, without sound. */
void engine_step(long long instructions);
void engine_forward(long long cycles);
/* Back by instructions / cycles. Returns 0 if the history (~10 s) does not reach that far;
 * then the oldest point kept is restored. */
int engine_back_instructions(long long n);
int engine_back_time(long long cycles);
double engine_history_seconds(void);
/* After going back, going forward replays the recorded controls; this drops the recording
 * from the current point on, so live controls take over (call when normal play resumes). */
void engine_forget_future(void);

/* Where we are: emulated time in cycles, instructions and frames since open. */
long long engine_time(void);
long long engine_instructions(void);
long long engine_frames(void);
/* Start time and address of the last executed instruction (-1 if none yet). */
long long engine_last_start(void);
int engine_last_pc(void);
/* A X Y S P PC before the next instruction. */
void engine_regs(int out[6]);
/* Scan line and cycle of the beam (stop inside a frame); returns 0 at a frame boundary. */
int engine_beam(int out[2]);
int engine_mid_frame(void);

/* Current picture, ENGINE_WIDTH x ENGINE_HEIGHT ARGB. Inside a frame the lines below the
 * beam still show the previous frame, as on a real TV. */
void engine_picture(uint32_t *argb);

/* Memory-access trace: times of the last read/write/execution of every byte. */
void engine_trace(int on);
void engine_trace_clear(void);
void engine_trace_copy(long long *reads, long long *writes, long long *execs);
void engine_trace_arrays(const long long **reads, const long long **writes, const long long **execs);

/* The core's 64 KB memory array (RAM plus mapped ROM), or NULL. */
uint8_t *engine_memory(void);

/* Machine state at a frame boundary (a stop inside a frame saves the start of that frame).
 * Loading clears the history. */
size_t engine_state_size(void);
int engine_save(void *data, size_t size);
int engine_load(const void *data, size_t size);
#endif
