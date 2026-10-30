/*
 * Time control for the Atari800 core, so the Atari app can do what the Specialist and ZX
 * emulators do: run at any speed down to one cycle per second, stop between any two
 * instructions, step forward and back, and show a live memory map.
 *
 * The core can only run whole frames. To stop inside a frame, the instruction hook in cpu.c
 * (ALEX_instruction) leaves the core with longjmp once the requested point is reached. The
 * machine's state is then incomplete (ANTIC was in the middle of a frame), so it is never
 * continued directly: the state saved at the start of that frame ("scratch") is restored and
 * the frame is run again up to the next stopping point. Emulation is deterministic, so the
 * repeated part is identical. Going back works the same way from snapshots kept every
 * SNAP_EVERY frames for the last ~62 s (at least a minute), with the controls of every frame
 * logged for replay.
 *
 * Time is counted in machine cycles (1.77 MHz PAL, 114 per scan line, 312 lines per frame)
 * from the moment the program was opened. GPL-2.0-or-later, see atari/COPYING.
 */
#include "engine.h"
#include "engine_internal.h"
#include "alexspec_hooks.h"
#include "antic.h"
#include "cpu.h"
#include "screen.h"
#include <setjmp.h>
#include <stdlib.h>
#include <string.h>

#define SNAP_EVERY 25
#define RING 125                /* 125 x 25 frames = 3125 frames, about 62 s (~35 MB) */
#define LOG 4096                /* controls of the last frames, more than RING * SNAP_EVERY */
#define SCREEN_BYTES (Screen_WIDTH * Screen_HEIGHT)

/* ---- trace (used by the hooks in cpu.c) ---- */

int ALEX_hooks, ALEX_trace;
long long ALEX_stamp;
long long ALEX_last_read[65536], ALEX_last_write[65536], ALEX_last_exec[65536];

#define NEVER (-(1LL << 60))

/* ---- position ---- */

static long long instr;              /* instructions executed since open */
static long long cur_frame;          /* number of the frame now running or about to start */
static int mid;                      /* stopped inside cur_frame; the machine must not be continued */
static long long pos_time;           /* emulated time now */
static long long last_start = NEVER; /* start time of the last executed instruction */
static int last_pc = -1;
static int regs[6];                  /* A X Y S P PC before the next instruction (when mid) */
static int beam_line, beam_cycle;    /* scan line and cycle of the stop (when mid) */

/* stop request for the hook */
static int stop_armed;
static long long stop_instr, stop_time;
static jmp_buf stop_jump;

/* ---- snapshots ---- */

typedef struct {
	long long frame, instr;
	unsigned char *state;            /* engine_snapshot_size() bytes */
	unsigned char *screen;           /* Screen_atari as it was at the start of the frame */
} Snap;

static Snap scratch;                 /* start of cur_frame; valid when have_scratch */
static int have_scratch;
static Snap ring[RING];
static int ring_head, ring_count;    /* ring[ring_head - 1] is the newest */
static size_t snap_size;

static EngineInput inputs[LOG];      /* controls of frames [log_first, log_end) */
static long long log_first, log_end;
static EngineInput live = {-1, 0, 7, 0};

static int16_t *audio_out;
static int audio_cap, audio_count;

long long engine_frame_cycles(void) { return (long long) Atari800_tv_mode * ANTIC_LINE_C; }

static long long frame_base(long long f) { return f * engine_frame_cycles(); }

/* Emulated time of the instruction about to start. */
static long long now_time(void)
{
	return frame_base(cur_frame) + (long long) ANTIC_ypos * ANTIC_LINE_C + ANTIC_XPOS;
}

void ALEX_instruction(unsigned pc, unsigned a, unsigned x, unsigned y, unsigned s, unsigned p)
{
	long long t = now_time();
	if (stop_armed && (instr >= stop_instr || t >= stop_time)) {
		regs[0] = a; regs[1] = x; regs[2] = y; regs[3] = s; regs[4] = p; regs[5] = pc;
		beam_line = ANTIC_ypos;
		beam_cycle = ANTIC_XPOS;
		pos_time = t;
		longjmp(stop_jump, 1);
	}
	last_start = t;
	last_pc = (int) pc;
	instr++;
	ALEX_stamp = t + 1;
}

/* The hook also counts instructions (needed to step back by instructions), so it always runs. */
static void update_hooks(void) { ALEX_hooks = 1; }

/* ---- snapshots ---- */

static int snap_alloc(Snap *s)
{
	if (!s->state) s->state = malloc(snap_size);
	if (!s->screen) s->screen = malloc(SCREEN_BYTES);
	return s->state && s->screen;
}

static void snap_free(Snap *s)
{
	free(s->state);
	free(s->screen);
	s->state = NULL;
	s->screen = NULL;
}

static int snap_take(Snap *s)
{
	if (!snap_alloc(s) || !engine_snapshot_save(s->state, snap_size)) return 0;
	memcpy(s->screen, Screen_atari, SCREEN_BYTES);
	s->frame = cur_frame;
	s->instr = instr;
	return 1;
}

static int snap_restore(const Snap *s)
{
	if (!engine_snapshot_load(s->state, snap_size)) return 0;
	memcpy(Screen_atari, s->screen, SCREEN_BYTES);
	cur_frame = s->frame;
	instr = s->instr;
	return 1;
}

static void snap_copy(Snap *dst, const Snap *src)
{
	if (!snap_alloc(dst)) return;
	memcpy(dst->state, src->state, snap_size);
	memcpy(dst->screen, src->screen, SCREEN_BYTES);
	dst->frame = src->frame;
	dst->instr = src->instr;
}

static Snap *ring_at(int k) /* k = 0 newest */
{
	return &ring[(ring_head + RING - 1 - k) % RING];
}

/* ---- controls ---- */

void engine_set_input(const EngineInput *in) { live = *in; }

static EngineInput input_for(long long f)
{
	if (f >= log_first && f < log_end) return inputs[f % LOG];
	/* f == log_end: a new frame; remember what it was played with */
	inputs[f % LOG] = live;
	log_end = f + 1;
	if (log_end - log_first > LOG) log_first = log_end - LOG;
	return live;
}

/* ---- running ---- */

static size_t audio_sink(const int16_t *data, size_t count)
{
	if (!audio_out) return count;
	if (count > (size_t) (audio_cap - audio_count)) count = audio_cap - audio_count;
	memcpy(audio_out + audio_count, data, count * sizeof(int16_t));
	audio_count += (int) count;
	return count;
}

/* Runs one frame of the core under the stop request; returns 1 if it stopped inside. */
static int frame_or_stop(const EngineInput *in)
{
	stop_armed = 1;
	update_hooks();
	if (setjmp(stop_jump)) {
		stop_armed = 0;
		update_hooks();
		return 1;
	}
	engine_frame_body(in);
	stop_armed = 0;
	update_hooks();
	return 0;
}

/*
 * Runs forward from the current position until instr >= target_instr or the time reaches
 * target_time (always to an instruction boundary; a frame boundary also counts).
 */
static void run_until(long long target_instr, long long target_time)
{
	stop_instr = target_instr;
	stop_time = target_time;
	for (;;) {
		if (mid) {
			/* continue inside cur_frame: start it again from its beginning */
			if (!have_scratch || !snap_restore(&scratch)) {
				mid = 0;
				return;
			}
		} else {
			if (instr >= target_instr || pos_time >= target_time) return;
			have_scratch = snap_take(&scratch);
			if (have_scratch && cur_frame % SNAP_EVERY == 0
					&& (ring_count == 0 || ring_at(0)->frame < cur_frame)) {
				snap_copy(&ring[ring_head], &scratch);
				ring_head = (ring_head + 1) % RING;
				if (ring_count < RING) ring_count++;
			}
		}
		EngineInput in = input_for(cur_frame);
		engine_set_audio_sink(audio_sink);
		int stopped = frame_or_stop(&in);
		engine_set_audio_sink(NULL);
		if (stopped) {
			mid = 1;
			return;
		}
		cur_frame++;
		mid = 0;
		pos_time = frame_base(cur_frame);
	}
}

/* Forgets the logged controls and snapshots after the current position. Until then, going
   forward again after going back replays exactly what happened (like a recording); after it,
   the following frames are played with the live controls. */
void engine_forget_future(void)
{
	long long keep = mid ? cur_frame + 1 : cur_frame;
	if (log_end > keep) log_end = keep < log_first ? log_first : keep;
	while (ring_count > 0 && ring_at(0)->frame > cur_frame) {
		ring_head = (ring_head + RING - 1) % RING;
		ring_count--;
	}
}

/* Goes back to the point where instr >= target_instr or time >= target_time is first true. */
static int seek_back(long long target_instr, long long target_time)
{
	Snap *best = NULL;
	int ok = 0;
	for (int k = 0; k < ring_count; k++) {
		Snap *s = ring_at(k);
		best = s;
		if (s->instr <= target_instr && frame_base(s->frame) <= target_time) {
			ok = 1;
			break;
		}
	}
	if (!best || !snap_restore(best)) return 0;
	mid = 0;
	have_scratch = 0;
	pos_time = frame_base(cur_frame);
	last_start = NEVER;
	last_pc = -1;
	if (ok) run_until(target_instr, target_time);
	return ok;
}

void timeline_reset(void)
{
	instr = 0;
	cur_frame = 0;
	mid = 0;
	pos_time = 0;
	last_start = NEVER;
	last_pc = -1;
	have_scratch = 0;
	ring_head = ring_count = 0;
	log_first = log_end = 0;
	stop_armed = 0;
	update_hooks();
	size_t need = engine_snapshot_size();
	if (need != snap_size) {
		snap_free(&scratch);
		for (int i = 0; i < RING; i++) snap_free(&ring[i]);
		snap_size = need;
	}
}

void timeline_free(void)
{
	snap_free(&scratch);
	for (int i = 0; i < RING; i++) snap_free(&ring[i]);
	snap_size = 0;
	ring_head = ring_count = 0;
	have_scratch = 0;
}

/* ---- public API ---- */

int engine_run(long long cycles, int to_frame_end, int16_t *audio, int audio_capacity)
{
	if (!engine_is_open()) return -1;
	audio_out = audio;
	audio_cap = audio ? audio_capacity : 0;
	audio_count = 0;
	long long target = to_frame_end ? frame_base(cur_frame + 1) : pos_time + (cycles < 1 ? 1 : cycles);
	run_until(1LL << 62, target);
	audio_out = NULL;
	return audio_count;
}

void engine_step(long long n)
{
	if (!engine_is_open() || n <= 0) return;
	run_until(instr + n, 1LL << 62);
}

void engine_forward(long long cycles)
{
	if (!engine_is_open() || cycles <= 0) return;
	run_until(1LL << 62, pos_time + cycles);
}

int engine_back_instructions(long long n)
{
	if (!engine_is_open()) return 0;
	return seek_back(instr - n, 1LL << 62);
}

int engine_back_time(long long cycles)
{
	if (!engine_is_open()) return 0;
	return seek_back(1LL << 62, pos_time - cycles);
}

long long engine_time(void) { return pos_time; }
long long engine_instructions(void) { return instr; }
long long engine_frames(void) { return cur_frame; }
long long engine_last_start(void) { return last_start; }
int engine_last_pc(void) { return last_pc; }
int engine_mid_frame(void) { return mid; }

void engine_regs(int out[6])
{
	if (mid) {
		memcpy(out, regs, sizeof(regs));
		return;
	}
	CPU_GetStatus();
	out[0] = CPU_regA; out[1] = CPU_regX; out[2] = CPU_regY;
	out[3] = CPU_regS; out[4] = CPU_regP; out[5] = CPU_regPC;
}

int engine_beam(int out[2])
{
	out[0] = mid ? beam_line : 0;
	out[1] = mid ? beam_cycle : 0;
	return mid;
}

double engine_history_seconds(void)
{
	if (ring_count == 0) return 0;
	Snap *oldest = ring_at(ring_count - 1);
	return (pos_time - frame_base(oldest->frame)) / (double) ENGINE_CPU_HZ;
}

void engine_trace(int on)
{
	if (on && !ALEX_trace) engine_trace_clear();
	ALEX_trace = on;
	update_hooks();
}

void engine_trace_clear(void)
{
	for (int i = 0; i < 65536; i++) ALEX_last_read[i] = ALEX_last_write[i] = ALEX_last_exec[i] = NEVER;
}

void engine_trace_arrays(const long long **reads, const long long **writes, const long long **execs)
{
	*reads = ALEX_last_read;
	*writes = ALEX_last_write;
	*execs = ALEX_last_exec;
}

void engine_trace_copy(long long *reads, long long *writes, long long *execs)
{
	if (reads) memcpy(reads, ALEX_last_read, sizeof(ALEX_last_read));
	if (writes) memcpy(writes, ALEX_last_write, sizeof(ALEX_last_write));
	if (execs) memcpy(execs, ALEX_last_exec, sizeof(ALEX_last_exec));
}

/* While stopped inside a frame, the machine itself is not in a savable state: a save stores the
   start of that frame instead (without moving the current position). NULL otherwise. */
const void *timeline_mid_frame_snapshot(void)
{
	return mid && have_scratch ? scratch.state : NULL;
}
