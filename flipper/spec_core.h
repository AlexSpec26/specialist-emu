#pragma once
/*
 * ПК «Специалист» — compact emulation core for small devices (Flipper Zero).
 * Plain C99, no dependencies; the same file is built for the desktop tests.
 */
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define SPEC_CPU_HZ        2000000
#define SPEC_FPS           50
#define SPEC_FRAME_CYCLES  (SPEC_CPU_HZ / SPEC_FPS)
#define SPEC_W             384
#define SPEC_H             256
#define SPEC_ROM_SIZE      0x3000

/* Keyboard: 12 columns x 6 rows, code = col * 6 + row; SPEC_KEY_SHIFT is the separate НР key. */
#define SPEC_COLS      12
#define SPEC_ROWS      6
#define SPEC_KEY_SHIFT (SPEC_COLS * SPEC_ROWS)
#define SPEC_KEY_COUNT (SPEC_KEY_SHIFT + 1)
#define SPEC_KEY(col, row) ((col) * SPEC_ROWS + (row))

#define SPEC_K_ENTER SPEC_KEY(0, 0)
#define SPEC_K_BKSP  SPEC_KEY(0, 1)
#define SPEC_K_LF    SPEC_KEY(1, 0)
#define SPEC_K_RIGHT SPEC_KEY(2, 0)
#define SPEC_K_LEFT  SPEC_KEY(4, 0)
#define SPEC_K_SPACE SPEC_KEY(5, 0)
#define SPEC_K_ESC   SPEC_KEY(6, 0)
#define SPEC_K_DOWN  SPEC_KEY(8, 0)
#define SPEC_K_UP    SPEC_KEY(9, 0)
#define SPEC_K_F1    SPEC_KEY(11, 5)

typedef struct {
    uint16_t pc, sp;
    uint8_t a, b, c, d, e, h, l;
    uint8_t fs, fz, fac, fp, fcy;
    uint8_t halted, inte, ei_pending;
} I8080;

typedef struct {
    I8080 cpu;
    uint8_t* mem;           /* 64 KB: RAM 0000-BFFF (video 9000-BFFF), ROM C000-EFFF */
    bool rom_loaded;
    /* PPI КР580ВВ55 */
    uint8_t port_a, port_b, port_c;
    bool a_in, b_in, clo_in, chi_in;
    /* keyboard */
    uint8_t key_cols[SPEC_COLS];
    bool shift;
    uint8_t press_count[SPEC_KEY_COUNT];
    /* beeper */
    bool beeper;
    uint32_t beeper_toggles;  /* counted since last spec_take_toggles() */
    /* last write into video RAM (for "follow the cursor") */
    uint16_t last_vwrite;
} Spec;

/* Allocates nothing; caller provides a 64 KB buffer. */
void spec_init(Spec* m, uint8_t* mem64k);
void spec_load_rom(Spec* m, const uint8_t* data, size_t len);
void spec_reset(Spec* m);
void spec_hard_reset(Spec* m);

/* Runs at least `cycles` CPU cycles. */
void spec_run(Spec* m, int32_t cycles);

void spec_key_down(Spec* m, int code);
void spec_key_up(Spec* m, int code);
void spec_release_all(Spec* m);

uint32_t spec_take_toggles(Spec* m);

/*
 * Loads an .rks image. Returns false if the file is not a program.
 * run = true: reset, let the monitor initialise, then jump to the start address.
 */
bool spec_load_rks(Spec* m, const uint8_t* data, size_t len, bool run, uint16_t* start, uint16_t* end);

/*
 * Streaming .rks loading (for devices without RAM for the whole file):
 * parse the header from the first bytes, then poke data with spec_poke().
 * Returns false if not a program; *data_off = offset of the first data byte.
 */
bool spec_rks_header(const uint8_t* head, size_t n, size_t* data_off, uint16_t* beg, uint16_t* end);
/* Reset and let the monitor initialise (call before poking a program you will run). */
void spec_prepare_run(Spec* m);
/* Writes a byte through the normal bus (RAM only below C000). */
void spec_poke(Spec* m, uint16_t addr, uint8_t v);
/* Starts execution at addr with keys released. */
void spec_start_at(Spec* m, uint16_t addr);

/* ---- rendering to a 128x64 1-bpp XBM buffer (16 bytes per row, LSB = leftmost) ---- */
typedef enum {
    SPEC_VIEW_1X = 0,  /* 1:1 window 128x64, pan in 8 px steps horizontally */
    SPEC_VIEW_2X,      /* 256x128 area, 2x2 -> 1 */
    SPEC_VIEW_FULL,    /* whole screen, 3x4 -> 1 */
    SPEC_VIEW_COUNT
} SpecView;

void spec_render(const Spec* m, uint8_t* xbm, SpecView view, int vx, int vy);

/* Size of the area visible in the given mode, in Specialist pixels. */
void spec_view_size(SpecView view, int* w, int* h);

/* CPU only (for tests) */
int i8080_step(Spec* m);
/* Tests: make the whole 64 KB plain RAM (no ROM / PPI). */
void spec_core_flat_memory(bool flat);
