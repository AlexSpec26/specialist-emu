/*
 * Desktop test of the Atari engine (atari/src/main/jni/native) with the real Atari800 core and
 * its freely distributed Altirra OS/BASIC: boot, keyboard, joystick, sound, file formats, and
 * the time control (slow motion, single steps, going back) and memory trace that the app's
 * Specialist/ZX-style tools rely on.
 *
 *   make -s -f atari/src/main/jni/Makefile.host test DEMO=atari/src/main/assets/demos/catch-the-star.xex
 *
 * GPL-2.0-or-later, see atari/COPYING.
 */
#include "engine.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#define DIR "/tmp/atari-native-system"
#define AKEY_NONE (-1)
#define CONSOL_NONE 7

static uint32_t pixels[ENGINE_WIDTH * ENGINE_HEIGHT];
static int16_t audio[16384];
static long loud;

static void input(int akey, int buttons) {
    EngineInput in = {akey, 0, CONSOL_NONE, buttons};
    engine_set_input(&in);
}

/* n whole frames at normal speed with the current controls; counts loud samples */
static void frames(int n) {
    for (int i = 0; i < n; i++) {
        int count = engine_run(0, 1, audio, 16384);
        assert(count > 0 && count <= 16384);
        for (int j = 0; j < count; j++) if (audio[j] > 200 || audio[j] < -200) loud++;
    }
}

static void screenshot(const char *path) {
    engine_picture(pixels);
    FILE *f = fopen(path, "wb");
    assert(f);
    fprintf(f, "P6\n%d %d\n255\n", ENGINE_WIDTH, ENGINE_HEIGHT);
    for (int i = 0; i < ENGINE_WIDTH * ENGINE_HEIGHT; i++) {
        unsigned char p[3] = {pixels[i] >> 16, pixels[i] >> 8, pixels[i]};
        fwrite(p, 1, 3, f);
    }
    fclose(f);
}

/* screen codes, ignoring inverse video and letter case */
static int screen_has(const unsigned char *codes, int n) {
    uint8_t *ram = engine_memory();
    int screen = ram[0x58] | ram[0x59] << 8;
    for (int i = 0; i < 960; i++) {
        int k = 0;
        while (k < n && (ram[(screen + i + k) & 0xffff] & 0x3f) == codes[k]) k++;
        if (k == n) return 1;
    }
    return 0;
}

static unsigned long hash_ram(void) {
    uint8_t *ram = engine_memory();
    unsigned long h = 1469598103934665603UL;
    for (int i = 0; i < 65536; i++) h = (h ^ ram[i]) * 1099511628211UL;
    return h;
}

/* controls of the demo for frame f: a scripted walk with fire */
static int script(long long f) {
    if (f % 40 < 15) return 1 << 7;          /* right */
    if (f % 40 < 25) return 1 << 5 | 1;      /* down + fire */
    if (f % 40 < 30) return 1 << 6;          /* left */
    return 0;
}

/* keys for BASIC in frame f: types A, B, C, ... with pauses (keyboard interrupts) */
static int typing(long long f) {
    static const int letters[] = {0x3f, 0x15, 0x12, 0x3a, 0x2a, 0x38};
    return f % 6 < 3 ? letters[(f / 6) % 6] : AKEY_NONE;
}

static void test_basic(void) {
    assert(engine_open(DIR, "", 1));
    input(AKEY_NONE, 0);
    frames(220);
    assert(engine_fps() > 49 && engine_fps() < 51);
    assert(engine_frame_cycles() == 312 * 114);
    const unsigned char ready[] = {0x32, 0x25, 0x21, 0x24, 0x39}; /* "Ready" */
    screenshot("/tmp/atari-basic.ppm");
    assert(screen_has(ready, 5));
    /* PRINT 123 + RETURN as Atari key codes */
    const int keys[] = {0x0a, 0x28, 0x0d, 0x23, 0x2d, 0x21, 0x1f, 0x1e, 0x1a, 0x0c};
    for (unsigned i = 0; i < sizeof(keys) / sizeof(*keys); i++) {
        input(keys[i], 0);
        frames(4);
        input(AKEY_NONE, 0);
        frames(4);
    }
    frames(20);
    screenshot("/tmp/atari-typed.ppm");
    uint8_t *ram = engine_memory();
    int screen = ram[0x58] | ram[0x59] << 8, printed = 0;
    for (int i = 0; i < 958; i++)
        if ((ram[(screen + i) & 0xffff] & 0x7f) == 0x11 && (ram[(screen + i + 1) & 0xffff] & 0x7f) == 0x12
            && (ram[(screen + i + 2) & 0xffff] & 0x7f) == 0x13) printed++;
    assert(printed >= 2); /* the typed command and the result */
    puts("BASIC BOOT + KEYBOARD + PICTURE OK");
}

static void test_demo(const char *demo) {
    assert(engine_open(DIR, demo, 0));
    input(AKEY_NONE, 0);
    frames(400);
    uint8_t *ram = engine_memory();
    assert(ram[0x81] == 4 && ram[0x82] == 4);
    input(AKEY_NONE, 1 << 7);
    frames(16);
    assert(ram[0x81] > 4); /* joystick right moves the player */
    loud = 0;
    input(AKEY_NONE, 1);
    frames(16);
    assert(loud > 100); /* fire beeps through POKEY */
    engine_picture(pixels);
    int colours = 0;
    uint32_t palette[32];
    for (int i = 0; i < ENGINE_WIDTH * ENGINE_HEIGHT; i++) {
        int j;
        for (j = 0; j < colours; j++) if (palette[j] == pixels[i]) break;
        if (j == colours && colours < 32) palette[colours++] = pixels[i];
    }
    assert(colours >= 3);
    screenshot("/tmp/atari-demo.ppm");
    puts("XEX + JOYSTICK + POKEY SOUND + PICTURE OK");
}

/* Slow motion must give exactly the same machine as running whole frames. */
static void test_slow_motion_is_exact(const char *content, int basic, int use_keys) {
    assert(engine_open(DIR, content, basic));
    input(AKEY_NONE, 0);
    frames(300);
    size_t n = engine_state_size();
    unsigned char *start = malloc(n);
    assert(start && engine_save(start, n));
    long long f0 = engine_frames();

    /* reference: whole frames */
    unsigned long ref_hash[60];
    int ref_regs[60][6];
    for (int k = 0; k < 60; k++) {
        if (use_keys) input(typing(f0 + k), 0);
        else input(AKEY_NONE, script(f0 + k));
        frames(1);
        ref_hash[k] = hash_ram();
        engine_regs(ref_regs[k]);
    }

    /* the same frames in random slices, stopping between instructions all the time */
    assert(engine_load(start, n));
    assert(engine_frames() == 0);  /* loading starts the timeline anew */
    srand(7);
    long long stops = 0;
    for (int k = 0; k < 60; k++) {
        long long f = engine_frames();
        if (use_keys) input(typing(f0 + k), 0);
        else input(AKEY_NONE, script(f0 + k));
        while (engine_frames() == f) {
            long long left = (f + 1) * engine_frame_cycles() - engine_time();
            long long slice = 1 + rand() % 4000;
            if (slice >= left) engine_run(0, 1, audio, 16384);
            else {
                engine_run(slice, 0, audio, 16384);
                assert(engine_mid_frame());
                stops++;
            }
        }
        assert(hash_ram() == ref_hash[k]);
        int r[6];
        engine_regs(r);
        assert(memcmp(r, ref_regs[k], sizeof r) == 0);
    }
    assert(stops > 500);
    free(start);
    printf("SLOW MOTION = WHOLE FRAMES, %s (%lld stops inside frames) OK\n",
           use_keys ? "BASIC + KEYBOARD" : "GAME + JOYSTICK", stops);
}

/* Single steps forward and back, seconds back and forward. */
static void test_steps(const char *demo) {
    assert(engine_open(DIR, demo, 0));
    engine_trace(1);
    for (int k = 0; k < 400; k++) {
        input(AKEY_NONE, script(k));
        frames(1);
    }
    engine_run(12345, 0, audio, 16384);
    assert(engine_mid_frame());

    /* one instruction forward and back */
    int r0[6], r1[6], r2[6];
    long long i0 = engine_instructions(), t0 = engine_time();
    engine_regs(r0);
    engine_step(1);
    assert(engine_instructions() == i0 + 1 && engine_time() > t0);
    assert(engine_last_pc() == r0[5]);                 /* executed the instruction at old PC */
    engine_regs(r1);
    engine_step(1);
    assert(engine_back_instructions(1));
    engine_regs(r2);
    assert(engine_instructions() == i0 + 1 && memcmp(r1, r2, sizeof r1) == 0);
    assert(engine_back_instructions(1));
    engine_regs(r2);
    assert(engine_instructions() == i0 && engine_time() == t0 && memcmp(r0, r2, sizeof r0) == 0);

    /* the trace marks the last instruction as executed, at its start time + 1 */
    long long x[65536];
    engine_step(1);
    engine_trace_copy(NULL, NULL, x);
    assert(x[r0[5]] == engine_last_start() + 1);

    /* a second back and a second forward: the same memory as before */
    unsigned long h = hash_ram();
    long long t = engine_time(), i = engine_instructions();
    assert(engine_back_time(ENGINE_CPU_HZ));
    assert(engine_time() >= t - ENGINE_CPU_HZ && engine_time() < t - ENGINE_CPU_HZ + 2 * ENGINE_LINE_CYCLES);
    engine_forward(t - engine_time());
    assert(engine_instructions() == i && hash_ram() == h); /* the recorded controls are replayed */

    /* after forgetting the recording, live controls take over */
    assert(engine_back_time(ENGINE_CPU_HZ / 2));
    engine_forget_future();
    input(AKEY_NONE, 0);
    engine_forward(t - engine_time());
    assert(hash_ram() != h);

    /* history: at least a minute back, not more */
    for (int k = 0; k < 3300; k++) frames(1);
    double hs = engine_history_seconds();
    assert(hs >= 60 && hs < 63.5); /* at least a minute */
    long long before = engine_time();
    assert(engine_back_time(55LL * ENGINE_CPU_HZ) && before - engine_time() <= 55LL * ENGINE_CPU_HZ);
    assert(!engine_back_time(20LL * ENGINE_CPU_HZ));
    puts("STEPS FORWARD/BACK + SECONDS BACK/FORWARD + TRACE OK");
}

static void test_save_load(const char *demo) {
    assert(engine_open(DIR, demo, 0));
    input(AKEY_NONE, 0);
    frames(300);
    unsigned long h = hash_ram();            /* at the start of a frame */
    engine_run(5000, 0, audio, 16384);       /* a save inside a frame stores its start... */
    long long t = engine_time();
    size_t n = engine_state_size();
    void *state = malloc(n);
    assert(n > 10000 && state && engine_save(state, n));
    assert(engine_mid_frame() && engine_time() == t); /* ...and does not move the position */
    input(AKEY_NONE, 1 << 7);
    frames(20);
    assert(hash_ram() != h);
    assert(engine_load(state, n));
    assert(hash_ram() == h);
    unsigned char bad[16] = {0};
    assert(!engine_load(bad, sizeof bad));
    free(state);
    /* stopped inside a frame, then acted on from outside: load, reset, a new machine */
    engine_run(7000, 0, audio, 16384);
    assert(engine_mid_frame());
    state = malloc(n);
    assert(state && engine_save(state, n));
    engine_run(3000, 0, audio, 16384);
    assert(engine_load(state, n));
    engine_run(9000, 0, audio, 16384);
    engine_reset();
    frames(10);
    engine_run(5000, 0, audio, 16384);
    assert(engine_mid_frame());
    assert(engine_open(DIR, demo, 0));
    frames(10);
    free(state);
    puts("SAVE/LOAD OK (also from inside a frame)");
}

static void test_media(void) {
    /* a minimal original boot disk exercises the ATR/SIO path */
    static unsigned char disk[16 + 720 * 128];
    disk[0] = 0x96; disk[1] = 2; disk[2] = 0x80; disk[3] = 0x16; disk[4] = 128;
    unsigned char boot[] = {0, 1, 0, 7, 6, 7, 0xa9, 0x55, 0x8d, 0, 9, 0x18, 0x60};
    memcpy(disk + 16, boot, sizeof boot);
    FILE *f = fopen(DIR "/test.atr", "wb");
    assert(f);
    fwrite(disk, 1, sizeof disk, f);
    fclose(f);
    assert(engine_open(DIR, DIR "/test.atr", 0));
    input(AKEY_NONE, 0);
    frames(400);
    assert(engine_memory()[0x900] == 0x55);
    puts("ATR BOOT + SIO OK");

    /* an original standard 8K cartridge with INIT/RUN vectors */
    static unsigned char cart[16 + 8192];
    memcpy(cart, "CART", 4);
    cart[7] = 1;
    unsigned char init[] = {0xa9, 0x66, 0x8d, 1, 9, 0x60};
    memcpy(cart + 16, init, sizeof init);
    unsigned char run[] = {0xa9, 0x67, 0x8d, 2, 9, 0x4c, 0x10, 0xa0};
    memcpy(cart + 16 + 16, run, sizeof run);
    cart[16 + 0x1ffa] = 0x10; cart[16 + 0x1ffb] = 0xa0; cart[16 + 0x1ffc] = 0;
    cart[16 + 0x1ffd] = 4; cart[16 + 0x1ffe] = 0; cart[16 + 0x1fff] = 0xa0;
    unsigned sum = 0;
    for (int i = 16; i < (int)sizeof cart; i++) sum += cart[i];
    cart[8] = sum >> 24; cart[9] = sum >> 16; cart[10] = sum >> 8; cart[11] = sum;
    f = fopen(DIR "/test.car", "wb");
    assert(f);
    fwrite(cart, 1, sizeof cart, f);
    fclose(f);
    assert(engine_open(DIR, DIR "/test.car", 0));
    frames(250);
    assert(engine_memory()[0x901] == 0x66 && engine_memory()[0x902] == 0x67);
    puts("CAR INIT/RUN OK");

    /* the same cartridge as a raw image (.rom, no header): the type comes from the core's
       database or the size; it must not be opened a second time as "unknown type" */
    f = fopen(DIR "/test.rom", "wb");
    assert(f);
    fwrite(cart + 16, 1, 8192, f);
    fclose(f);
    assert(engine_open(DIR, DIR "/test.rom", 0));
    assert(!engine_is_5200());
    frames(250);
    assert(engine_memory()[0x901] == 0x66 && engine_memory()[0x902] == 0x67);
    engine_memory()[0x901] = 0;
    engine_reset();                          /* cold start keeps the cartridge */
    frames(250);
    assert(engine_memory()[0x901] == 0x66);
    puts("RAW ROM CARTRIDGE + RESET OK");

    engine_close();
    assert(!engine_open(DIR, "/no/such/file.xex", 0));
    assert(engine_run(0, 1, audio, 16384) == -1);
    puts("BAD FILE HANDLING OK");
}

/* An Atari 5200 cartridge (.a52): the core switches to the 5200 console; joystick and keypad. */
static void test_5200(void) {
    static unsigned char cart[8192];
    unsigned char code[] = {
        0xad, 0x10, 0xc0, 0x8d, 0x01, 0x09,   /* LDA TRIG0 ($C010) : STA $0901 */
        0xad, 0x09, 0xe8, 0x8d, 0x02, 0x09,   /* LDA KBCODE ($E809): STA $0902 */
        0xee, 0x03, 0x09,                     /* INC $0903 */
        0x4c, 0x00, 0xa0};                    /* JMP $A000 */
    memcpy(cart, code, sizeof code);
    cart[0x1ffd] = 0xff;                      /* skip the title screen, start at once */
    cart[0x1ffe] = 0x00; cart[0x1fff] = 0xa0;
    FILE *f = fopen(DIR "/test.a52", "wb");
    assert(f);
    fwrite(cart, 1, sizeof cart, f);
    fclose(f);
    assert(engine_open(DIR, DIR "/test.a52", 0));
    assert(engine_is_5200());
    input(AKEY_NONE, 0);
    frames(200);
    uint8_t *ram = engine_memory();
    assert(ram[0x903] != 0 && ram[0x901] == 1);   /* running, fire not pressed */
    input(AKEY_NONE, 1);
    frames(5);
    assert(ram[0x901] == 0);                       /* fire */
    EngineInput start = {AKEY_NONE, 0, CONSOL_NONE & ~1, 0};
    engine_set_input(&start);
    frames(5);
    assert((ram[0x902] & 0x1f) == (0x39 & 0x1f));  /* START -> keypad START */
    input(0x1e, 0);                                /* "2" -> keypad 2 */
    frames(5);
    assert((ram[0x902] & 0x1f) == (0x3d & 0x1f));
    /* time control works on the 5200 too */
    engine_run(4000, 0, audio, 16384);
    long long t = engine_time();
    engine_step(1);
    assert(engine_back_instructions(1) && engine_time() == t);
    /* and the next program is an 800XL again */
    assert(engine_open(DIR, "", 1) && !engine_is_5200());
    puts("ATARI 5200 CARTRIDGE + JOYSTICK + KEYPAD + STEPS OK");
}

/* plays `cycles` of emulated time with the scripted joystick, in slices like the app */
static void play(long long cycles) {
    long long end = engine_time() + cycles;
    while (engine_time() < end) {
        input(AKEY_NONE, script(engine_frames()));
        long long left = end - engine_time();
        engine_run(left < 20000 ? left : 20000, 0, audio, 16384);
    }
}

typedef struct {
    long long instr, time;
    unsigned long ram;
    int regs[6];
    double player;
} Mark;

static Mark mark(void) {
    Mark m;
    m.instr = engine_instructions();
    m.time = engine_time();
    m.ram = hash_ram();
    engine_regs(m.regs);
    m.player = engine_player_time();
    return m;
}

static int same(Mark a, Mark b) {
    return a.instr == b.instr && a.time == b.time && a.ram == b.ram && memcmp(a.regs, b.regs, sizeof a.regs) == 0;
}

/* ATARI-008: back 5 s of the player's clock, exactly, at any speed; saves keep the history. */
static void test_player_rewind(const char *demo) {
    const double x1 = 1.0 / ENGINE_CPU_HZ;
    assert(engine_open(DIR, demo, 0));
    engine_set_player_rate(x1);
    play(8LL * ENGINE_CPU_HZ + 12345);        /* 8 s, then stop inside a frame */
    assert(engine_mid_frame());

    /* x1: 5 s of play, back 5 s = the same machine as 5 s ago (to the cycle) */
    /* (play stops at the first instruction at or after its end, a few cycles later: going back
       by what the clock shows lands exactly on the mark) */
    Mark m = mark();
    play(5LL * ENGINE_CPU_HZ);
    double went = engine_back_player(engine_player_time() - m.player);
    assert(went > 4.9999 && went < 5.0001);
    assert(same(m, mark()));

    /* x4: 20 s of the game are 5 s of the player's */
    engine_forget_future();
    engine_set_player_rate(x1 / 4);
    m = mark();
    play(20LL * ENGINE_CPU_HZ);
    engine_set_player_rate(x1);
    went = engine_back_player(engine_player_time() - m.player);
    assert(went > 4.9999 && went < 5.0001 && same(m, mark()));

    /* slow motion 1/8: 0.625 s of the game */
    engine_forget_future();
    engine_set_player_rate(x1 * 8);
    m = mark();
    play(ENGINE_CPU_HZ * 5LL / 8);
    went = engine_back_player(engine_player_time() - m.player);
    assert(went > 4.9999 && went < 5.0001 && same(m, mark()));

    /* three times in a row, the clock and the game time go back 15 s */
    engine_forget_future();
    engine_set_player_rate(x1);
    play(30LL * ENGINE_CPU_HZ);
    m = mark();
    for (int i = 0; i < 3; i++) {
        went = engine_back_player(5);
        assert(went > 4.9999 && went < 5.0001);
    }
    assert(m.time - engine_time() >= 15LL * ENGINE_CPU_HZ - 200 && m.time - engine_time() <= 15LL * ENGINE_CPU_HZ);
    assert(fabs(m.player - engine_player_time() - 15) < 2e-5); /* each lands on the next instruction start */
    /* going forward again replays the recording: the same machine as before */
    engine_forward(m.time - engine_time());
    assert(same(m, mark()));

    /* not further than the history */
    engine_forget_future();
    play(70LL * ENGINE_CPU_HZ);
    double hist = engine_history_player_seconds();
    assert(hist > 60 && hist < 63.5);
    went = engine_back_player(1000);
    assert(went > 60 && went < 63.5);
    assert(engine_back_player(5) == 0 || engine_history_player_seconds() < 0.6);

    /* a save keeps the last minute: loaded into a new machine, back 5 s gives the same as here */
    engine_forget_future();
    play(70LL * ENGINE_CPU_HZ + 777);
    assert(engine_mid_frame());
    size_t n;
    unsigned char *state = engine_save_history(&n);
    assert(state && n > 1000);
    printf("  save with %.1f s of history: %zu KB\n", engine_history_player_seconds(), n / 1024);
    assert(n < 8u << 20);
    Mark at_save = mark();
    went = engine_back_player(5);
    Mark back_here = mark();
    engine_forget_future();
    play(3LL * ENGINE_CPU_HZ);
    Mark on_here = mark();

    assert(engine_open(DIR, demo, 0));
    engine_set_player_rate(x1);
    play(ENGINE_CPU_HZ);
    unsigned long other = hash_ram();
    /* damaged files are refused and leave the machine alone */
    assert(!engine_load(state, n - 100));
    state[n / 2] ^= 0x55;
    assert(!engine_load(state, n));
    state[n / 2] ^= 0x55;
    assert(hash_ram() == other);

    assert(engine_load(state, n));
    assert(!engine_mid_frame());
    /* a save inside a frame stores the start of that frame */
    assert(engine_time() <= at_save.time && engine_time() > at_save.time - engine_frame_cycles());
    assert(fabs(engine_player_time() - at_save.player) < 0.03);
    assert(engine_history_player_seconds() > 58);
    /* back 5 s from the loaded point, in this machine and in a freshly opened one: the same;
       and playing on from there (the same controls) gives the same game twice. A fresh open
       followed by a load once went its own way here: the XEX loader's state was not in the
       snapshot (now it is, binload.c in THIRD_PARTY.md). */
    Mark loaded = mark();
    assert(engine_back_player(5) > 4.9999);
    Mark back_loaded = mark();
    /* compare with the original machine doing the same from the same point */
    assert(engine_open(DIR, demo, 0));
    assert(engine_load(state, n));
    assert(same(loaded, mark()));
    assert(engine_back_player(5) > 4.9999 && same(back_loaded, mark()));
    engine_forget_future();
    play(3LL * ENGINE_CPU_HZ);
    Mark on_loaded = mark();
    assert(engine_load(state, n) && engine_back_player(5) > 4.9999);
    assert(same(back_loaded, mark()));
    engine_forget_future();
    play(3LL * ENGINE_CPU_HZ);
    assert(same(on_loaded, mark()));
    (void)back_here; (void)on_here; (void)went;

    /* steps back work after loading too */
    assert(engine_load(state, n));
    long long i0 = engine_instructions();
    assert(engine_back_instructions(1) && engine_instructions() == i0 - 1);
    assert(engine_back_time(30LL * ENGINE_CPU_HZ));

    /* an old save without history still loads ("ATS1"), also one from before ATARI-008 that
       has no XEX-loader state (8 ints less before the core's state) */
    size_t n1 = engine_state_size();
    unsigned char *old = malloc(n1);
    assert(old && engine_save(old, n1));
    unsigned long h1 = hash_ram();
    play(ENGINE_CPU_HZ);
    assert(engine_load(old, n1) && engine_history_player_seconds() == 0 && hash_ram() == h1);
    size_t legacy = n1 - (engine_snapshot_extra() - 8 * sizeof(int));
    memmove(old + 4 + 8 * sizeof(int), old + 4 + engine_snapshot_extra(), n1 - 4 - engine_snapshot_extra());
    play(ENGINE_CPU_HZ);
    assert(engine_load(old, legacy) && hash_ram() == h1);
    assert(!engine_load(old, legacy + 4));
    free(old);

    /* going back to the moment the program was still loading (the XEX loader in the middle of
       the file) and playing on gives the same machine as the first time */
    assert(engine_open(DIR, demo, 0));
    play(6LL * ENGINE_CPU_HZ);
    m = mark();
    for (int k = 1; k <= 6; k++) { /* 0.25 s … 1.5 s after the start */
        engine_back_time(engine_time() - k * ENGINE_CPU_HZ / 4);
        engine_forward(m.time - engine_time());
        assert(same(m, mark()));
    }
    free(state);
    puts("PLAYER CLOCK: BACK 5 S AT x1, x4, 1/8, 3 TIMES, NOT FURTHER THAN HISTORY; SAVE WITH HISTORY OK");
}

int main(int argc, char **argv) {
    assert(argc == 2);
    mkdir(DIR, 0700);
    test_basic();
    test_demo(argv[1]);
    for (int i = 0; i < 3; i++) assert(engine_open(DIR, argv[1], 0)); /* repeated opens */
    test_slow_motion_is_exact(argv[1], 0, 0);
    test_slow_motion_is_exact("", 1, 1);
    test_steps(argv[1]);
    test_save_load(argv[1]);
    test_player_rewind(argv[1]);
    test_media();
    test_5200();
    puts("ATARI ENGINE OK");
    return 0;
}
