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
 * from the moment the program was opened.
 *
 * The player's clock (ATARI-008, as ADR-031 in ZX) counts the time the player lived through:
 * every emulated cycle adds as many seconds as the app sets from the speed (1/CPU_HZ at x1, a
 * quarter of it at x4, more in slow motion). Every change of that rate is logged with its exact
 * cycle, so the clock is an exact function of the emulated time, and "back 5 s" finds the very
 * instruction 5 s of the player's time ago, whatever the speed was and however often it changed.
 * GPL-2.0-or-later, see atari/COPYING.
 */
#include "engine.h"
#include "engine_internal.h"
#include "alexspec_hooks.h"
#include "antic.h"
#include "cpu.h"
#include "screen.h"
#include <math.h>
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
	double player;                   /* the player's clock at the start of that frame */
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

/* the player's clock: the rate changes, oldest first; the clock at time t is
   p + (t - time) * rate of the last change at or before t */
typedef struct {
	long long time;
	double p, rate;
} RateChange;
#define RATES 4096
static RateChange rates[RATES];
static int rate_count;
static double player;                /* at pos_time */

static double player_at(long long t)
{
	int i = rate_count - 1;
	while (i > 0 && rates[i].time > t) i--;
	return rates[i].p + (double) (t - rates[i].time) * rates[i].rate;
}

/* The emulated time at which the clock reaches p (the first cycle at or after it); -1 if before
   the first change kept. */
static long long time_of_player(double p)
{
	int i = rate_count - 1;
	while (i > 0 && rates[i].p > p) i--;
	if (rates[i].p > p) return -1;
	double dt = (p - rates[i].p) / rates[i].rate;
	long long t = rates[i].time + (long long) ceil(dt - 1e-6);
	if (i + 1 < rate_count && t > rates[i + 1].time) t = rates[i + 1].time;
	return t;
}

/* Forgets rate changes after t (going back): the future is played again. */
static void rates_cut_after(long long t)
{
	while (rate_count > 1 && rates[rate_count - 1].time > t) rate_count--;
}

/* Keeps the rate changes the history needs: from the last one at or before t on. */
static void rates_drop_before(long long t)
{
	int k = 0;
	while (k + 1 < rate_count && rates[k + 1].time <= t) k++;
	if (k == 0) return;
	memmove(rates, rates + k, (size_t) (rate_count - k) * sizeof *rates);
	rate_count -= k;
}

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
	s->player = player;
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
	dst->player = src->player;
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
		/* ANTIC was left in the middle of a scan line. The machine is never continued from
		 * here (the frame is run again from its start), but code outside the frame loop -
		 * reset, a new machine - must not see "drawing": ANTIC_PutByte would then index the
		 * cycle maps with the stale position. ANTIC_Frame sets this again at its start. */
		ANTIC_cur_screen_pos = ANTIC_NOT_DRAWING;
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
	/* the player's clock grows linearly with the emulated time during one call */
	for (;;) {
		if (mid) {
			/* continue inside cur_frame: start it again from its beginning */
			if (!have_scratch || !snap_restore(&scratch)) {
				mid = 0;
				break;
			}
		} else {
			if (instr >= target_instr || pos_time >= target_time) break;
			player = player_at(pos_time);
			have_scratch = snap_take(&scratch);
			if (have_scratch && cur_frame % SNAP_EVERY == 0
					&& (ring_count == 0 || ring_at(0)->frame < cur_frame)) {
				snap_copy(&ring[ring_head], &scratch);
				ring_head = (ring_head + 1) % RING;
				if (ring_count < RING) ring_count++;
				rates_drop_before(frame_base(ring_at(ring_count - 1)->frame));
			}
		}
		EngineInput in = input_for(cur_frame);
		engine_set_audio_sink(audio_sink);
		int stopped = frame_or_stop(&in);
		engine_set_audio_sink(NULL);
		if (stopped) {
			mid = 1;
			break;
		}
		cur_frame++;
		mid = 0;
		pos_time = frame_base(cur_frame);
	}
	player = player_at(pos_time);
}

/* Drops the snapshots after the current position (going back): their player's clock belongs to
   a future that is played again; the logged controls stay until engine_forget_future. */
static void drop_newer_snapshots(void)
{
	while (ring_count > 0 && ring_at(0)->frame > cur_frame) {
		ring_head = (ring_head + RING - 1) % RING;
		ring_count--;
	}
}

/* Forgets the logged controls and snapshots after the current position. Until then, going
   forward again after going back replays exactly what happened (like a recording); after it,
   the following frames are played with the live controls. */
void engine_forget_future(void)
{
	long long keep = mid ? cur_frame + 1 : cur_frame;
	if (log_end > keep) log_end = keep < log_first ? log_first : keep;
	drop_newer_snapshots();
}

/*
 * Goes back to the point where instr >= target_instr, time >= target_time or the player's clock
 * >= target_player is first true (the first one reached), replaying from the latest snapshot
 * before it. Returns 0 if the history does not reach; then the oldest point kept is restored.
 */
static int seek_back(long long target_instr, long long target_time, double target_player)
{
	if (target_player < 1e300) {
		long long t = time_of_player(target_player);
		if (t < 0) t = NEVER;
		if (t < target_time) target_time = t;
	}
	int age = -1, ok = 0;
	for (int k = 0; k < ring_count; k++) {
		Snap *s = ring_at(k);
		age = k;
		if (s->instr <= target_instr && frame_base(s->frame) <= target_time) {
			ok = 1;
			break;
		}
	}
	if (age < 0) return 0;
	Snap *s = ring_at(age);
	if (!snap_restore(s)) return 0;
	mid = 0;
	have_scratch = 0;
	pos_time = frame_base(s->frame);
	player = player_at(pos_time);
	last_start = NEVER;
	last_pc = -1;
	if (ok) run_until(target_instr, target_time);
	drop_newer_snapshots();
	rates_cut_after(pos_time);
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
	player = 0;
	double r = rate_count ? rates[rate_count - 1].rate : 1.0 / ENGINE_CPU_HZ;
	rates[0] = (RateChange) {0, 0, r};
	rate_count = 1;
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
	return seek_back(instr - n, 1LL << 62, HUGE_VAL);
}

int engine_back_time(long long cycles)
{
	if (!engine_is_open()) return 0;
	return seek_back(1LL << 62, pos_time - cycles, HUGE_VAL);
}

double engine_back_player(double seconds)
{
	if (!engine_is_open() || ring_count == 0 || seconds <= 0) return 0;
	double from = player;
	seek_back(1LL << 62, 1LL << 62, from - seconds); /* not that far: the oldest point kept */
	return from - player;
}

void engine_set_player_rate(double seconds_per_cycle)
{
	if (!(seconds_per_cycle > 0) || !isfinite(seconds_per_cycle)) return;
	RateChange *last = &rates[rate_count - 1];
	if (last->rate == seconds_per_cycle) return;
	if (last->time == pos_time) {
		last->rate = seconds_per_cycle;
		return;
	}
	if (rate_count == RATES) rates_drop_before(pos_time); /* hardly ever: thousands of changes */
	if (rate_count == RATES) {
		memmove(rates, rates + 1, (RATES - 1) * sizeof *rates);
		rate_count--;
	}
	double now = player_at(pos_time);
	rates[rate_count] = (RateChange) {pos_time, now, seconds_per_cycle};
	rate_count++;
}

double engine_player_time(void) { return player; }

double engine_history_player_seconds(void)
{
	if (ring_count == 0) return 0;
	return player - player_at(frame_base(ring_at(ring_count - 1)->frame));
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

/* Before something acts on the machine from outside the timeline (reset, loading a state):
   go back to the start of the frame we are stopped in, a consistent machine. */
void timeline_leave_frame(void)
{
	if (!mid) return;
	if (have_scratch) snap_restore(&scratch);
	mid = 0;
	pos_time = frame_base(cur_frame);
	player = player_at(pos_time);
	rates_cut_after(pos_time);
}

/* While stopped inside a frame, the machine itself is not in a savable state: a save stores the
   start of that frame instead (without moving the current position). NULL otherwise. */
const void *timeline_mid_frame_snapshot(void)
{
	return mid && have_scratch ? scratch.state : NULL;
}

/* ---- saves with a minute of history (ATARI-008) ----
 *
 * Payload (zlib-compressed by the caller's container, see engine.c "ATS2"):
 *   u32 version 2, u32 snapshot size, u32 screen bytes,
 *   current point: i64 frame, i64 instr, f64 player, state, screen,
 *   u32 n, then n snapshots oldest first: i64 frame, i64 instr, f64 player, state, screen,
 *   i64 first logged frame, i64 count, then count x 4 x i32 controls (akey, shift, consol, buttons),
 *   u32 m, then m changes of the player's clock rate: i64 time, f64 clock, f64 seconds per cycle.
 * Every second snapshot of the ring is kept (one per second of play) and the controls from the
 * oldest of them on, so going back works after loading the same way as before saving.
 */

#define SAVE_VERSION 2
#define SAVE_SNAP_EVERY 2

typedef struct {
	unsigned char *p;
	size_t len, cap;
	int bad;
} Buf;

static void put(Buf *b, const void *data, size_t n)
{
	if (b->bad) return;
	if (b->len + n > b->cap) {
		size_t cap = b->cap ? b->cap : 1 << 20;
		while (cap < b->len + n) cap *= 2;
		unsigned char *q = realloc(b->p, cap);
		if (!q) {
			b->bad = 1;
			return;
		}
		b->p = q;
		b->cap = cap;
	}
	memcpy(b->p + b->len, data, n);
	b->len += n;
}

static void put_u32(Buf *b, uint32_t v) { put(b, &v, 4); }
static void put_i64(Buf *b, long long v) { int64_t x = v; put(b, &x, 8); }
static void put_f64(Buf *b, double v) { put(b, &v, 8); }

static void put_point(Buf *b, long long frame, long long ins, double pl, const void *state, const void *screen)
{
	put_i64(b, frame);
	put_i64(b, ins);
	put_f64(b, pl);
	put(b, state, snap_size);
	put(b, screen, SCREEN_BYTES);
}

void *timeline_save_payload(size_t *out_len)
{
	*out_len = 0;
	if (!snap_size) return NULL;
	Buf b = {0};
	put_u32(&b, SAVE_VERSION);
	put_u32(&b, (uint32_t) snap_size);
	put_u32(&b, (uint32_t) SCREEN_BYTES);
	/* the current point: inside a frame, its start (as engine_save) */
	long long at_frame;
	if (mid && have_scratch) {
		at_frame = scratch.frame;
		put_point(&b, scratch.frame, scratch.instr, scratch.player, scratch.state, scratch.screen);
	} else {
		unsigned char *st = malloc(snap_size);
		if (!st || !engine_snapshot_save(st, snap_size)) {
			free(st);
			free(b.p);
			return NULL;
		}
		at_frame = cur_frame;
		put_point(&b, cur_frame, instr, player, st, Screen_atari);
		free(st);
	}
	/* snapshots before that point, one per second, whose controls are still logged */
	int pick[RING], n = 0;
	for (int k = 0; k < ring_count && log_end >= at_frame; k++) {
		Snap *s = ring_at(k);
		if (s->frame > at_frame || s->frame < log_first) continue;
		if (n > 0 && k % SAVE_SNAP_EVERY != pick[0] % SAVE_SNAP_EVERY) continue;
		pick[n++] = k;
	}
	put_u32(&b, (uint32_t) n);
	for (int i = n - 1; i >= 0; i--) {
		Snap *s = ring_at(pick[i]);
		put_point(&b, s->frame, s->instr, s->player, s->state, s->screen);
	}
	long long first = n ? ring_at(pick[n - 1])->frame : at_frame;
	long long count = at_frame - first;
	put_i64(&b, first);
	put_i64(&b, count);
	for (long long f = first; f < first + count; f++) {
		const EngineInput *in = &inputs[f % LOG];
		int32_t v[4] = {in->akey, in->shift, in->consol, in->buttons};
		put(&b, v, sizeof v);
	}
	put_u32(&b, (uint32_t) rate_count);
	for (int i = 0; i < rate_count; i++) {
		put_i64(&b, rates[i].time);
		put_f64(&b, rates[i].p);
		put_f64(&b, rates[i].rate);
	}
	if (b.bad) {
		free(b.p);
		return NULL;
	}
	*out_len = b.len;
	return b.p;
}

typedef struct {
	const unsigned char *p;
	size_t len, at;
} Rd;

static const void *take(Rd *r, size_t n)
{
	if (r->at + n > r->len || r->at + n < r->at) return NULL;
	const void *q = r->p + r->at;
	r->at += n;
	return q;
}

static int get_u32(Rd *r, uint32_t *v) { const void *q = take(r, 4); if (!q) return 0; memcpy(v, q, 4); return 1; }
static int get_i64(Rd *r, long long *v) { int64_t x; const void *q = take(r, 8); if (!q) return 0; memcpy(&x, q, 8); *v = x; return 1; }
static int get_f64(Rd *r, double *v) { const void *q = take(r, 8); if (!q) return 0; memcpy(v, q, 8); return 1; }

typedef struct {
	long long frame, instr;
	double player;
	const unsigned char *state, *screen;
} Point;

static int get_point(Rd *r, Point *pt)
{
	return get_i64(r, &pt->frame) && get_i64(r, &pt->instr) && get_f64(r, &pt->player)
		&& (pt->state = take(r, snap_size)) && (pt->screen = take(r, SCREEN_BYTES))
		&& pt->frame >= 0 && pt->instr >= 0 && isfinite(pt->player);
}

/*
 * Checks the whole payload first and touches nothing if it is wrong (wrong machine, cut or
 * damaged file); then loads the current point and rebuilds the history. Returns 1 on success.
 */
int timeline_load_payload(const void *data, size_t len)
{
	size_t need = engine_snapshot_size();
	if (!need || need != snap_size) return 0;
	Rd r = {data, len, 0};
	uint32_t version, ssize, sbytes, n;
	Point cur, snaps[RING];
	long long first, count;
	if (!get_u32(&r, &version) || version != SAVE_VERSION) return 0;
	if (!get_u32(&r, &ssize) || ssize != snap_size) return 0;
	if (!get_u32(&r, &sbytes) || sbytes != SCREEN_BYTES) return 0;
	if (!get_point(&r, &cur)) return 0;
	if (!get_u32(&r, &n) || n > RING) return 0;
	for (uint32_t i = 0; i < n; i++) {
		if (!get_point(&r, &snaps[i])) return 0;
		if (snaps[i].frame > cur.frame || snaps[i].instr > cur.instr || snaps[i].player > cur.player) return 0;
		if (i > 0 && (snaps[i].frame <= snaps[i - 1].frame || snaps[i].player < snaps[i - 1].player)) return 0;
	}
	if (!get_i64(&r, &first) || !get_i64(&r, &count)) return 0;
	if (count < 0 || count >= LOG || first < 0 || first + count != cur.frame) return 0;
	if (n > 0 && first != snaps[0].frame) return 0;
	const int32_t *log = take(&r, (size_t) count * 16);
	uint32_t m;
	if (!log || !get_u32(&r, &m) || m < 1 || m > RATES) return 0;
	static RateChange rc[RATES];
	for (uint32_t i = 0; i < m; i++) {
		if (!get_i64(&r, &rc[i].time) || !get_f64(&r, &rc[i].p) || !get_f64(&r, &rc[i].rate)) return 0;
		if (!isfinite(rc[i].p) || !(rc[i].rate > 0) || !isfinite(rc[i].rate)) return 0;
		if (i > 0 && (rc[i].time <= rc[i - 1].time || rc[i].p < rc[i - 1].p)) return 0;
	}
	if (r.at != r.len) return 0;
	if (rc[0].time > (n ? frame_base(snaps[0].frame) : frame_base(cur.frame))) return 0;

	timeline_leave_frame();
	if (!engine_snapshot_load(cur.state, snap_size)) return 0;
	timeline_reset();
	memcpy(Screen_atari, cur.screen, SCREEN_BYTES);
	cur_frame = cur.frame;
	instr = cur.instr;
	pos_time = frame_base(cur_frame);
	memcpy(rates, rc, m * sizeof *rates);
	rate_count = (int) m;
	rates_cut_after(pos_time);
	player = player_at(pos_time);
	for (uint32_t i = 0; i < n; i++) {
		Snap *s = &ring[ring_head];
		if (!snap_alloc(s)) break;
		memcpy(s->state, snaps[i].state, snap_size);
		memcpy(s->screen, snaps[i].screen, SCREEN_BYTES);
		s->frame = snaps[i].frame;
		s->instr = snaps[i].instr;
		s->player = snaps[i].player;
		ring_head = (ring_head + 1) % RING;
		ring_count++;
	}
	for (long long i = 0; i < count; i++) {
		int32_t v[4];
		memcpy(v, log + 4 * i, sizeof v);
		EngineInput *in = &inputs[(first + i) % LOG];
		in->akey = v[0];
		in->shift = v[1];
		in->consol = v[2];
		in->buttons = v[3];
	}
	log_first = first;
	log_end = first + count;
	engine_trace_clear();
	return 1;
}
