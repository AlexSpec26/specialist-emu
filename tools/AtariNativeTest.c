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
    test_media();
    test_5200();
    puts("ATARI ENGINE OK");
    return 0;
}
