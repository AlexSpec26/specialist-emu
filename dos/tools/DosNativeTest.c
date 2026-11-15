/*
 * Desktop test of the DOS engine (dos/src/main/jni/native): boot of a program, picture, sound,
 * keys, rewinding by 5 seconds on the player's clock (slow motion), a minute of history, saved
 * states with the history; with "" as the program, the start menu.
 * The core starts only once per process (a second engine_open crashes inside DOSBox), so the
 * app runs each game in a process of its own.
 * Build and run: make -f dos/src/main/jni/Makefile.host test
 * GPL-2.0-or-later, see dos/COPYING.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "engine.h"
#include "libretro.h"

static int failures;
#define CHECK(c, ...) do { if (!(c)) { printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); failures++; } } while (0)

static int16_t audio[16384];
static uint32_t pic[ENGINE_MAX_W * ENGINE_MAX_H];

static uint64_t picture_hash(void) {
    int n = engine_picture(pic, ENGINE_MAX_W * ENGINE_MAX_H);
    uint64_t h = 1469598103934665603ULL;
    for (int i = 0; i < n; i++) h = (h ^ pic[i]) * 1099511628211ULL;
    return h;
}

static int run(int n) {
    int samples = 0;
    for (int i = 0; i < n; i++) samples += engine_frame(audio, (int)(sizeof audio / sizeof audio[0]));
    return samples;
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s DEMO.COM workdir\n", argv[0]);
        return 2;
    }
    const char *demo = argv[1], *dir = argv[2];
    mkdir(dir, 0755);
    if (strstr(demo, "MPU.COM")) {
        /* MPU.COM switches to 320x200 only when nothing answers at the MIDI port 331h */
        CHECK(engine_open(dir, dir, demo), "open %s", demo);
        run(120);
        CHECK(engine_width() == 320, "no MIDI card (MPU-401) without a synthesizer, got %dx%d", engine_width(), engine_height());
        printf("NO MIDI CARD: %s\n", failures ? "FAIL" : "OK");
        fflush(stdout);
        _exit(failures ? 1 : 0);
    }
    struct stat st_demo;
    char autoboot[1024];
    snprintf(autoboot, sizeof autoboot, "%s/AUTOBOOT.DBP", demo);
    if (stat(demo, &st_demo) == 0 && S_ISDIR(st_demo.st_mode) && access(autoboot, F_OK) == 0) {
        /* the app's choice of program (LaunchChoice): AUTOBOOT.DBP in the root of C: starts it at
         * once — no start menu, no keys */
        CHECK(engine_open(dir, dir, demo), "open folder %s", demo);
        run(150);
        CHECK(engine_width() == 320 && engine_height() == 200, "AUTOBOOT.DBP starts the program, got %dx%d", engine_width(), engine_height());
        printf("AUTOBOOT: %s\n", failures ? "FAIL" : "OK");
        fflush(stdout);
        _exit(failures ? 1 : 0);
    }
    if (stat(demo, &st_demo) == 0 && S_ISDIR(st_demo.st_mode)) {
        /* a game folder with two programs: the start menu lists them; Down + Enter starts the
         * second one (the demo, which switches to 320x200) — keys reach the core's menu */
        CHECK(engine_open(dir, dir, demo), "open folder %s", demo);
        run(90);
        CHECK(engine_width() != 320, "the start menu first, got %dx%d", engine_width(), engine_height());
        /* the menu ignores a choice made within 0.2 s (real time) of opening: wait like a person */
        usleep(400000);
        engine_key(RETROK_DOWN, 1); run(3); engine_key(RETROK_DOWN, 0); run(10);
        usleep(100000);
        engine_key(RETROK_RETURN, 1); run(3); engine_key(RETROK_RETURN, 0);
        run(150);
        CHECK(engine_width() == 320 && engine_height() == 200, "menu: Down + Enter starts the demo, got %dx%d", engine_width(), engine_height());
        printf("MENU KEYS: %s\n", failures ? "FAIL" : "OK");
        fflush(stdout);
        _exit(failures ? 1 : 0);
    }
    if (!*demo) { /* no program: the start menu with an empty drive */
        CHECK(engine_open(dir, dir, ""), "open without content");
        run(60);
        CHECK(engine_width() > 0 && engine_height() > 0, "start menu picture %dx%d", engine_width(), engine_height());
        size_t sz = engine_state_size();
        printf("START MENU %dx%d, state %zu: %s\n", engine_width(), engine_height(), sz, failures ? "FAIL" : "OK");
        fflush(stdout);
        _exit(failures ? 1 : 0);
    }

    /* boot */
    CHECK(engine_open(dir, dir, demo), "open %s", demo);
    int samples = run(140);
    double fps = engine_fps();
    CHECK(fps > 50 && fps < 80, "fps %.2f", fps);
    CHECK(engine_width() == 320 && engine_height() == 200, "picture %dx%d (mode 13h expected)", engine_width(), engine_height());
    double expect = 140 * engine_sample_rate() / fps * 2;
    CHECK(samples > expect * 0.9 && samples < expect * 1.1, "sound: %d samples, about %.0f expected", samples, expect);
    printf("BOOT %dx%d %.2f fps, %d Hz: OK\n", engine_width(), engine_height(), fps, engine_sample_rate());

    /* a key changes the colour of the line */
    uint64_t before = picture_hash();
    engine_key(RETROK_a, 1);
    run(4);
    engine_key(RETROK_a, 0);
    run(40);
    uint64_t after = picture_hash();
    CHECK(after != before, "the key changes the picture");
    int found = 0, n = engine_width() * engine_height();
    for (int i = 0; i < n; i++) if ((pic[i] & 0xFFFFFF) == 0x00AAAA || (pic[i] & 0xFFFFFF) == 0x0000AA) found = 1;
    printf("KEY: OK\n");
    (void)found;

    /* rewinding: a snapshot every 5 s, a minute kept. 70 s of play, then 5 s back */
    long long f0 = engine_frames();
    static uint64_t hashes[8000];
    int total = (int)(fps * 70);
    for (int i = 0; i < total; i++) {
        run(1);
        hashes[i] = picture_hash();
    }
    double hist = engine_history_seconds();
    CHECK(hist >= 60.0 && hist < 65.1, "history %.2f s (a minute at least)", hist);
    CHECK(engine_history_count() == 13, "13 snapshots, got %d", engine_history_count());
    double went = engine_rewind(5.0);
    CHECK(went >= 5.0 && went < 10.01, "rewind 5 s went %.3f s", went);
    /* the frames after the restored point repeat the recorded ones (DOSBox is not strictly
     * repeatable, so most of them, not all) */
    int same = 0;
    for (int k = 0; k < 40; k++) {
        long long fr = engine_frames();
        run(1);
        int idx = (int)(fr - f0);
        if (idx >= 0 && idx < total && hashes[idx] == picture_hash()) same++;
    }
    CHECK(same >= 30, "replayed frames equal to the recorded: %d of 40", same);
    went = engine_rewind(5.0);
    CHECK(went >= 5.0 && went < 5.7, "again 5 s back from just after a restored point: %.3f s", went);
    went = engine_rewind(5.0);
    CHECK(fabs(went - 5.0) < 0.01, "and again from the restored point itself: exactly 5 s, %.3f", went);
    printf("REWIND 5 s: OK (history %.1f s, replayed %d of 40 frames equal)\n", hist, same);

    /* slow motion: the history runs on the player's clock. 8 times slower: 5 player seconds
     * are 5/8 s of the game */
    engine_set_slow(8.0);
    long long fs = engine_frames();
    run((int)(fps * 3)); /* 3 s of the game = 24 s for the player */
    double t1 = engine_time();
    went = engine_rewind(5.0);
    long long back = engine_frames();
    CHECK(went >= 5.0 && went < 10.01, "slow ×8: 5 s back went %.3f player s", went);
    CHECK(fabs((t1 - engine_time()) - went) < 1e-6, "player clock goes back with the rewind");
    double game_s = (fs + fps * 3 - back) / fps;
    CHECK(fabs(game_s - went / 8) < 0.05, "slow ×8: %.3f player s back = %.3f game s (expected %.3f)", went, game_s, went / 8);
    double went8 = went;
    engine_set_slow(1.5);
    run((int)(fps * 12)); /* 18 player seconds */
    went = engine_rewind(5.0);
    CHECK(went >= 5.0 && went < 10.01, "slow ×1.5: 5 s back went %.3f", went);
    engine_set_slow(1.0);
    printf("SLOW MOTION CLOCK: OK (×8: %.2f s back = %.2f s of the game)\n", went8, game_s);

    /* beyond the history: the oldest point, then nothing older */
    run((int)(fps * 20));
    went = engine_rewind(1000.0);
    CHECK(went > 10.0, "rewind beyond the history goes to the oldest point: %.3f s", went);
    CHECK(engine_rewind(5.0) == 0, "nothing older than the oldest point");

    /* saved state with the history: after loading, a minute back is still there */
    run((int)(fps * 70));
    double hist_save = engine_history_seconds();
    int count_save = engine_history_count();
    size_t size = engine_state_size();
    CHECK(size > 1000 && size < 256u * 1024 * 1024, "state size %zu", size);
    void *st = malloc(size);
    CHECK(st && engine_save(st, size), "save");
    long long frame_save = engine_frames();
    run(300);
    CHECK(engine_load(st, size), "load");
    CHECK(engine_frames() == frame_save, "frame counter restored");
    CHECK(fabs(engine_history_seconds() - hist_save) < 1e-6 && engine_history_count() == count_save,
          "history restored: %.2f s / %d snapshots (saved %.2f / %d)", engine_history_seconds(), engine_history_count(), hist_save, count_save);
    went = engine_rewind(60.0);
    CHECK(went >= 60.0 && went < 65.5, "a minute back after loading: %.3f s", went);
    /* a bare core state (saves made before the history was saved) still loads, without history */
    size_t bare = retro_serialize_size();
    void *bs = malloc(bare);
    CHECK(bs && retro_serialize(bs, bare), "bare state");
    run(400);
    CHECK(engine_load(bs, bare), "load a bare core state");
    CHECK(engine_history_seconds() == 0 && engine_history_count() == 0, "history cleared by a bare state");
    run(10);
    CHECK(engine_history_count() == 1, "history grows again");
    /* a broken file loads nothing */
    memset((char *)st + 40, 0xFF, 8);
    CHECK(!engine_load(st, size), "a damaged state is refused");
    free(bs);
    printf("STATE %zu bytes with %d snapshots: OK\n", size, count_save);

    engine_close();
    free(st);
    if (failures) {
        printf("%d FAILURES\n", failures);
        return 1;
    }
    printf("DOS NATIVE OK\n");
    fflush(stdout);
    _exit(0); /* the core's emulation thread does not end on its own */
}
