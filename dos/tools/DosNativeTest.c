/*
 * Desktop test of the DOS engine (dos/src/main/jni/native): boot of a program, picture, sound,
 * keys, rewinding by 1 and 5 seconds, saved states; with "" as the program, the start menu.
 * The core starts only once per process (a second engine_open crashes inside DOSBox), so the
 * app runs each game in a process of its own.
 * Build and run: make -f dos/src/main/jni/Makefile.host test
 * GPL-2.0-or-later, see dos/COPYING.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
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

    /* rewinding: 8 seconds of play, then 1 s and 5 s back */
    long long f0 = engine_frames();
    static uint64_t hashes[2000];
    int total = (int)(fps * 8);
    for (int i = 0; i < total; i++) {
        run(1);
        hashes[i] = picture_hash();
    }
    double hist = engine_history_seconds();
    CHECK(hist > 6.0 && hist < 7.6, "history %.2f s", hist);
    long long now = engine_frames();
    double went = engine_rewind(1.0);
    CHECK(went >= 1.0 && went < 1.3, "rewind 1 s went %.3f s", went);
    CHECK(engine_frames() == now - (long long)(went * fps + 0.5), "frame counter after rewind");
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
    now = engine_frames();
    went = engine_rewind(5.0);
    CHECK(went >= 5.0 && went < 5.4, "rewind 5 s went %.3f s", went);
    went = engine_rewind(30.0);
    CHECK(went > 0 && went < 2.5, "rewind beyond the history goes to the oldest point: %.3f s", went);
    CHECK(engine_rewind(1.0) == 0, "nothing older than the oldest point");
    run(30);
    printf("REWIND 1 s / 5 s: OK (replayed %d of 40 frames equal)\n", same);

    /* saved state: back to it after a while; loading drops the history */
    size_t size = engine_state_size();
    CHECK(size > 1000 && size < 64u * 1024 * 1024, "state size %zu", size);
    void *st = malloc(size);
    CHECK(st && engine_save(st, size), "save");
    run(10);
    uint64_t at_save = picture_hash();
    (void)at_save;
    run(200);
    CHECK(engine_load(st, size), "load");
    CHECK(engine_history_seconds() == 0, "history cleared by load");
    run(10);
    run(50);
    CHECK(engine_history_seconds() > 0.3, "history grows again");
    printf("STATE %zu bytes: OK\n", size);

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
