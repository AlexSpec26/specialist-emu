/*
 * Desktop test for the Flipper core (flipper/spec_core.c).
 *   flipper_core_test cpu FILE.COM...              run CP/M 8080 diagnostics
 *   flipper_core_test run ROM OUT [RKS|-] SCRIPT    script items (comma separated):
 *       fN  run N frames      k:L[:N]  hold key L for N frames (L = Latin label, VK, SPC, F1..)
 *       t:TEXT  type text (Latin/digits/punct, '/' = Enter)    s  screenshot (full + 3 views)
 *       v:MODE:X:Y  set view for screenshots
 */
#include "../flipper/spec_core.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static uint8_t mem[0x10000];

static int cpu_test(const char* file) {
    FILE* f = fopen(file, "rb");
    if(!f) return 1;
    Spec m;
    spec_core_flat_memory(true);
    spec_init(&m, mem);
    memset(mem, 0, sizeof(mem));
    size_t n = fread(mem + 0x100, 1, 0xF000, f);
    fclose(f);
    (void)n;
    mem[5] = 0xC9;
    m.cpu.pc = 0x100;
    printf("=== %s\n", file);
    unsigned long long cyc = 0;
    for(;;) {
        if(m.cpu.pc == 5) {
            if(m.cpu.c == 2) putchar(m.cpu.e);
            else if(m.cpu.c == 9)
                for(uint16_t a = (uint16_t)((m.cpu.d << 8) | m.cpu.e); mem[a] != '$'; a++) putchar(mem[a]);
            fflush(stdout);
        } else if(m.cpu.pc == 0) break;
        cyc += (unsigned)i8080_step(&m);
    }
    printf("\ncycles=%llu\n", cyc);
    return 0;
}

/* Latin key labels in matrix order [col][row] */
static const char* LAB[12][6] = {
    {"VK", "ZB", ".", ":", "-", "STR"}, {"PS", "/", "\\", "H", "0", "F11"}, {"RIGHT", ",", "V", "Z", "9", "F10"},
    {"PV", "@", "D", "]", "8", "F9"},   {"LEFT", "B", "L", "[", "7", "F8"},  {"SPC", "X", "O", "G", "6", "F7"},
    {"AR2", "T", "R", "N", "5", "F6"},  {"TAB", "I", "P", "E", "4", "F5"},   {"DOWN", "M", "A", "K", "3", "F4"},
    {"UP", "S", "W", "U", "2", "F3"},   {"HOME", "^", "Y", "C", "1", "F2"},  {"RL", "Q", "F", "J", ";", "F1"},
};

static int key_by_label(const char* l) {
    if(!strcmp(l, "NR")) return SPEC_KEY_SHIFT;
    for(int c = 0; c < 12; c++)
        for(int r = 0; r < 6; r++)
            if(!strcmp(LAB[c][r], l)) return SPEC_KEY(c, r);
    return -1;
}

static Spec M;

static void frames(int n) {
    for(int i = 0; i < n; i++) spec_run(&M, SPEC_FRAME_CYCLES);
}

static void tap(int code, int hold) {
    spec_key_down(&M, code);
    frames(hold);
    spec_key_up(&M, code);
    frames(4);
}

static void write_pgm(const char* path, int w, int h, int (*get)(int, int, void*), void* ctx) {
    FILE* f = fopen(path, "wb");
    fprintf(f, "P5\n%d %d\n255\n", w, h);
    for(int y = 0; y < h; y++)
        for(int x = 0; x < w; x++) fputc(get(x, y, ctx) ? 255 : 0, f);
    fclose(f);
}

static int get_full(int x, int y, void* ctx) {
    (void)ctx;
    return (M.mem[0x9000 + (x >> 3) * 256 + y] >> (7 - (x & 7))) & 1;
}

static int get_xbm(int x, int y, void* ctx) {
    uint8_t* b = ctx;
    return (b[y * 16 + (x >> 3)] >> (x & 7)) & 1;
}

int main(int argc, char** argv) {
    if(argc >= 3 && !strcmp(argv[1], "cpu")) {
        for(int i = 2; i < argc; i++) cpu_test(argv[i]);
        return 0;
    }
    if(argc < 5) return 2;
    static uint8_t buf[0x20000];
    FILE* f = fopen(argv[2], "rb");
    size_t n = fread(buf, 1, sizeof(buf), f);
    fclose(f);
    spec_init(&M, mem);
    spec_load_rom(&M, buf, n);
    spec_hard_reset(&M);
    frames(50);
    if(strcmp(argv[4], "-")) {
        bool run = argv[4][0] != '@'; /* @file.rks = load into memory only */
        f = fopen(argv[4] + (run ? 0 : 1), "rb");
        n = fread(buf, 1, sizeof(buf), f);
        fclose(f);
        uint16_t s, e;
        bool ok = spec_load_rks(&M, buf, n, run, &s, &e);
        printf("rks %s %04X-%04X\n", ok ? "ok" : "BAD", s, e);
    }
    int shot = 0, view = 0, vx = 0, vy = 0;
    clock_t t0 = clock();
    long nfr = 0;
    char script[4096];
    strncpy(script, argc > 5 ? argv[5] : "f100,s", sizeof(script) - 1);
    for(char* it = strtok(script, ","); it; it = strtok(NULL, ",")) {
        if(it[0] == 'f') {
            int k = atoi(it + 1);
            frames(k);
            nfr += k;
        } else if(it[0] == 'k' && it[1] == ':') {
            char lab[16] = {0};
            int hold = 5;
            sscanf(it + 2, "%15[^:]:%d", lab, &hold);
            int code = key_by_label(lab);
            if(code < 0) { fprintf(stderr, "no key %s\n", lab); return 3; }
            tap(code, hold);
        } else if(it[0] == 't' && it[1] == ':') {
            for(char* p = it + 2; *p; p++) {
                char l[2] = {*p == '|' ? ',' : *p, 0};
                int code = *p == '/' ? SPEC_K_ENTER : key_by_label(l);
                if(code < 0) { fprintf(stderr, "no key '%c'\n", *p); return 3; }
                tap(code, 4);
            }
        } else if(it[0] == 'v' && it[1] == ':') {
            sscanf(it + 2, "%d:%d:%d", &view, &vx, &vy);
        } else if(it[0] == 's') {
            char path[512];
            snprintf(path, sizeof(path), "%s_%d_full.pgm", argv[3], shot);
            write_pgm(path, SPEC_W, SPEC_H, get_full, NULL);
            uint8_t xbm[1024];
            spec_render(&M, xbm, (SpecView)view, vx, vy);
            snprintf(path, sizeof(path), "%s_%d_view.pgm", argv[3], shot);
            write_pgm(path, 128, 64, get_xbm, xbm);
            printf("shot %d pc=%04X lastv=%04X\n", shot, M.cpu.pc, M.last_vwrite);
            shot++;
        }
    }
    double secs = (double)(clock() - t0) / CLOCKS_PER_SEC;
    printf("%ld frames %.3fs (%.1fx realtime)\n", nfr, secs, secs > 0 ? nfr / 50.0 / secs : 0);
    return 0;
}
