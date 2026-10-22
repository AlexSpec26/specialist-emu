/*
 * ПК «Специалист» for Flipper Zero.
 *
 * Controls
 *   View mode:     arrows pan the window, OK = keyboard, hold OK = change zoom, Back = menu
 *   Keyboard mode: arrows choose a key, OK presses it (held while OK is held), Back = view mode
 *   Joystick mode: arrows = Specialist arrows, OK = chosen key, Back = view mode
 *   Hold Back anywhere = exit
 */
#include "spec_core.h"

#include <furi.h>
#include <furi_hal_speaker.h>
#include <gui/gui.h>
#include <input/input.h>
#include <dialogs/dialogs.h>
#include <storage/storage.h>
#include <notification/notification_messages.h>

#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#define TAG "Specialist"
#define ASSETS_DIR EXT_PATH("apps_assets/specialist")
#define DATA_DIR   EXT_PATH("apps_data/specialist")

typedef enum { ModeView, ModeKbd, ModeJoy } UiMode;

typedef enum {
    MiKeyboard,
    MiJoystick,
    MiZoom,
    MiFollow,
    MiInvert,
    MiLoad,
    MiLoadRun,
    MiSpeed,
    MiSound,
    MiOkKey,
    MiReset,
    MiCold,
    MiExit,
    MiCount
} MenuItem;

static const float SPEEDS[] = {0.5f, 1.0f, 2.0f, 5.0f};
#define SPEED_COUNT 4

/* keys selectable for OK in joystick mode */
static const int OK_KEYS[] = {SPEC_K_SPACE, SPEC_K_ENTER, SPEC_K_F1, SPEC_K_ESC};
static const char* OK_KEY_NAMES[] = {"SPACE", "VK", "F1", "AR2"};
#define OK_KEY_COUNT 4

/* ---------------------------------------------------------------- keyboard layout
 * The physical keyboard mirrors the matrix: row r (top = 0) is matrix row 5 - r,
 * key c (left = 0) is matrix column 11 - c. The bottom row also gets the НР key. */
static const char* KEY_NAME[SPEC_COLS][SPEC_ROWS] = {
    {"VK", "ZB", ".", ":", "-", "STR"}, {"PS", "/", "\\", "H", "0", "F11"}, {"->", ",", "V", "Z", "9", "F10"},
    {"PV", "@", "D", "]", "8", "F9"},   {"<-", "B", "L", "[", "7", "F8"},   {"SPC", "X", "O", "G", "6", "F7"},
    {"AR2", "T", "R", "N", "5", "F6"},  {"TAB", "I", "P", "E", "4", "F5"},  {"Dn", "M", "A", "K", "3", "F4"},
    {"Up", "S", "W", "U", "2", "F3"},   {"HM", "^", "Y", "C", "1", "F2"},  {"R/L", "Q", "F", "J", ";", "F1"},
};

#define KBD_ROWS 6

static int kbd_row_len(int r) {
    return r == KBD_ROWS - 1 ? SPEC_COLS + 1 : SPEC_COLS;
}

static int kbd_code(int r, int c) {
    if(r == KBD_ROWS - 1) {
        if(c == 0) return SPEC_KEY_SHIFT;
        c--;
    }
    return SPEC_KEY(SPEC_COLS - 1 - c, SPEC_ROWS - 1 - r);
}

static const char* key_name(int code) {
    if(code == SPEC_KEY_SHIFT) return "NR";
    return KEY_NAME[code / SPEC_ROWS][code % SPEC_ROWS];
}

/* ---------------------------------------------------------------- app state */

typedef struct {
    int code;
    int frames; /* release after this many frames */
} PendingUp;

typedef struct {
    Spec m;
    uint8_t* mem;
    uint8_t xbm[1024];
    FuriMutex* mutex;
    FuriMessageQueue* queue;
    ViewPort* vp;
    Gui* gui;
    NotificationApp* notif;
    DialogsApp* dialogs;
    Storage* storage;
    FuriString* last_path;

    bool running;
    UiMode mode;
    bool menu;
    int menu_sel;

    SpecView view;
    int vx, vy;
    bool follow;
    bool invert;
    uint16_t followed_addr;

    int kbd_r, kbd_c;
    bool shift_latch;
    int held_code; /* key held by OK in keyboard / joystick mode, -1 = none */
    uint32_t held_frame;
    int joy_codes[4];
    PendingUp pending[4];

    int speed_idx;
    bool sound;
    bool speaker;
    float tone;
    int ok_key_idx;

    uint32_t frame_no;
    uint32_t emu_ms_acc, emu_frames_acc;
    int perf_pct;

    char toast[40];
    uint32_t toast_until;
} App;

static void toast(App* app, const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(app->toast, sizeof(app->toast), fmt, ap);
    va_end(ap);
    app->toast_until = furi_get_tick() + 1500;
}

/* ---------------------------------------------------------------- view helpers */

static void clamp_view(App* app) {
    int w, h;
    spec_view_size(app->view, &w, &h);
    int maxx = SPEC_W - w, maxy = SPEC_H - h;
    if(app->vx > maxx) app->vx = maxx;
    if(app->vy > maxy) app->vy = maxy;
    if(app->vx < 0) app->vx = 0;
    if(app->vy < 0) app->vy = 0;
    if(app->view == SPEC_VIEW_1X) app->vx &= ~7;
}

/* keep the last written video byte (normally the text cursor) inside the window */
static void follow_cursor(App* app) {
    if(!app->follow || app->view == SPEC_VIEW_FULL || app->mode == ModeJoy) return;
    uint16_t a = app->m.last_vwrite;
    if(a == app->followed_addr || a < 0x9000) return;
    app->followed_addr = a;
    int x = ((a - 0x9000) >> 8) * 8, y = (a - 0x9000) & 0xFF;
    int w, h;
    spec_view_size(app->view, &w, &h);
    int scale = app->view == SPEC_VIEW_2X ? 2 : 1;
    int visible_h = app->mode == ModeKbd ? h - 12 * scale : h; /* keyboard strip covers the bottom */
    int margin = 8 * scale;
    if(x < app->vx || x + 8 > app->vx + w) app->vx = x - w / 2;
    if(y < app->vy + margin / 2 || y + margin > app->vy + visible_h) app->vy = y - visible_h / 2;
    clamp_view(app);
}

/* ---------------------------------------------------------------- drawing */

static void draw_cb(Canvas* canvas, void* ctx) {
    App* app = ctx;
    furi_mutex_acquire(app->mutex, FuriWaitForever);
    canvas_clear(canvas);
    if(app->invert) {
        canvas_set_color(canvas, ColorBlack);
        canvas_draw_box(canvas, 0, 0, 128, 64);
        canvas_set_color(canvas, ColorWhite);
    } else {
        canvas_set_color(canvas, ColorBlack);
    }
    canvas_draw_xbm(canvas, 0, 0, 128, 64, app->xbm);
    canvas_set_color(canvas, ColorBlack);

    if(!app->m.rom_loaded) {
        canvas_set_color(canvas, ColorWhite);
        canvas_draw_box(canvas, 4, 18, 120, 28);
        canvas_set_color(canvas, ColorBlack);
        canvas_draw_frame(canvas, 4, 18, 120, 28);
        canvas_set_font(canvas, FontSecondary);
        canvas_draw_str_aligned(canvas, 64, 26, AlignCenter, AlignCenter, "No ROM. Put spec.rom");
        canvas_draw_str_aligned(canvas, 64, 37, AlignCenter, AlignCenter, "in apps_data/specialist");
    }

    if(app->mode == ModeKbd && !app->menu) {
        /* 7-key magnifier strip around the selected key */
        int n = kbd_row_len(app->kbd_r);
        canvas_set_color(canvas, ColorWhite);
        canvas_draw_box(canvas, 0, 52, 128, 12);
        canvas_set_color(canvas, ColorBlack);
        canvas_draw_line(canvas, 0, 52, 127, 52);
        canvas_set_font(canvas, FontKeyboard);
        for(int i = -3; i <= 3; i++) {
            int c = app->kbd_c + i;
            if(c < 0 || c >= n) continue;
            int x = 1 + (i + 3) * 18;
            int code = kbd_code(app->kbd_r, c);
            bool sel = i == 0;
            bool down = (code == SPEC_KEY_SHIFT && app->shift_latch) || code == app->held_code;
            if(sel || down) {
                canvas_draw_rbox(canvas, x, 53, 17, 11, 2);
                canvas_set_color(canvas, ColorWhite);
            } else {
                canvas_draw_rframe(canvas, x, 53, 17, 11, 2);
            }
            canvas_draw_str_aligned(canvas, x + 9, 59, AlignCenter, AlignCenter, key_name(code));
            canvas_set_color(canvas, ColorBlack);
        }
        /* row indicator */
        char rowtag[8];
        snprintf(rowtag, sizeof(rowtag), "%d%s", app->kbd_r + 1, app->shift_latch ? "N" : "");
        int w = canvas_string_width(canvas, rowtag) + 4;
        canvas_set_color(canvas, ColorWhite);
        canvas_draw_box(canvas, 127 - w, 43, w + 1, 9);
        canvas_set_color(canvas, ColorBlack);
        canvas_draw_frame(canvas, 127 - w, 43, w + 1, 10);
        canvas_draw_str_aligned(canvas, 128 - w / 2, 48, AlignCenter, AlignCenter, rowtag);
    }

    if(app->mode == ModeJoy && !app->menu) {
        canvas_set_color(canvas, ColorWhite);
        canvas_draw_box(canvas, 118, 0, 10, 9);
        canvas_set_color(canvas, ColorBlack);
        canvas_draw_frame(canvas, 118, 0, 10, 9);
        canvas_set_font(canvas, FontKeyboard);
        canvas_draw_str_aligned(canvas, 123, 4, AlignCenter, AlignCenter, "J");
    }

    if(app->menu) {
        static const int VISIBLE = 5;
        canvas_set_color(canvas, ColorWhite);
        canvas_draw_box(canvas, 6, 1, 116, 62);
        canvas_set_color(canvas, ColorBlack);
        canvas_draw_rframe(canvas, 6, 1, 116, 62, 3);
        canvas_set_font(canvas, FontPrimary);
        char title[32];
        snprintf(title, sizeof(title), "Specialist  %d%%", app->perf_pct);
        canvas_draw_str_aligned(canvas, 64, 8, AlignCenter, AlignCenter, title);
        canvas_set_font(canvas, FontSecondary);
        int first = app->menu_sel - VISIBLE / 2;
        if(first < 0) first = 0;
        if(first > MiCount - VISIBLE) first = MiCount - VISIBLE;
        static const char* ZOOMS[] = {"1:1", "1:2", "Full"};
        for(int i = 0; i < VISIBLE; i++) {
            int it = first + i;
            char line[32];
            switch(it) {
            case MiKeyboard: snprintf(line, sizeof(line), "Keyboard"); break;
            case MiJoystick: snprintf(line, sizeof(line), "Joystick mode"); break;
            case MiZoom: snprintf(line, sizeof(line), "Zoom: %s", ZOOMS[app->view]); break;
            case MiFollow: snprintf(line, sizeof(line), "Follow cursor: %s", app->follow ? "on" : "off"); break;
            case MiInvert: snprintf(line, sizeof(line), "Screen: %s", app->invert ? "dark" : "light"); break;
            case MiLoad: snprintf(line, sizeof(line), "Load .rks to memory"); break;
            case MiLoadRun: snprintf(line, sizeof(line), "Load & run .rks"); break;
            case MiSpeed: {
                int s10 = (int)(SPEEDS[app->speed_idx] * 10.0f + 0.5f);
                snprintf(line, sizeof(line), "Speed: %d.%dx", s10 / 10, s10 % 10);
                break;
            }
            case MiSound: snprintf(line, sizeof(line), "Sound: %s", app->sound ? "on" : "off"); break;
            case MiOkKey: snprintf(line, sizeof(line), "Joy OK key: %s", OK_KEY_NAMES[app->ok_key_idx]); break;
            case MiReset: snprintf(line, sizeof(line), "Reset"); break;
            case MiCold: snprintf(line, sizeof(line), "Cold start"); break;
            default: snprintf(line, sizeof(line), "Exit"); break;
            }
            int y = 16 + i * 10;
            if(it == app->menu_sel) {
                canvas_draw_rbox(canvas, 9, y - 1, 110, 10, 2);
                canvas_set_color(canvas, ColorWhite);
            }
            canvas_draw_str(canvas, 12, y + 7, line);
            canvas_set_color(canvas, ColorBlack);
        }
    }

    if(app->toast[0] && furi_get_tick() < app->toast_until) {
        canvas_set_font(canvas, FontSecondary);
        int w = canvas_string_width(canvas, app->toast) + 8;
        if(w > 128) w = 128;
        int x = 64 - w / 2;
        canvas_set_color(canvas, ColorWhite);
        canvas_draw_box(canvas, x, 0, w, 11);
        canvas_set_color(canvas, ColorBlack);
        canvas_draw_frame(canvas, x, 0, w, 11);
        canvas_draw_str_aligned(canvas, 64, 5, AlignCenter, AlignCenter, app->toast);
    }
    furi_mutex_release(app->mutex);
}

static void input_cb(InputEvent* ev, void* ctx) {
    App* app = ctx;
    furi_message_queue_put(app->queue, ev, 0);
}

/* ---------------------------------------------------------------- files */

static bool read_file(App* app, const char* path, uint8_t* dst, size_t max, size_t* out_len) {
    File* f = storage_file_alloc(app->storage);
    bool ok = false;
    if(storage_file_open(f, path, FSAM_READ, FSOM_OPEN_EXISTING)) {
        size_t n = storage_file_read(f, dst, max);
        *out_len = n;
        ok = n > 0;
    }
    storage_file_close(f);
    storage_file_free(f);
    return ok;
}

static void load_rom(App* app) {
    static const char* const CANDIDATES[] = {
        DATA_DIR "/spec.rom",
        ASSETS_DIR "/spec_m2.rom",
    };
    uint8_t* buf = malloc(SPEC_ROM_SIZE);
    for(size_t i = 0; i < COUNT_OF(CANDIDATES); i++) {
        size_t n = 0;
        if(read_file(app, CANDIDATES[i], buf, SPEC_ROM_SIZE, &n)) {
            spec_load_rom(&app->m, buf, n);
            spec_hard_reset(&app->m);
            FURI_LOG_I(TAG, "ROM %s (%u bytes)", CANDIDATES[i], (unsigned)n);
            break;
        }
    }
    free(buf);
}

/* Streams an .rks file into memory (no buffer for the whole file needed). */
static bool load_rks_file(App* app, const char* path, bool run, uint16_t* beg, uint16_t* end) {
    File* f = storage_file_alloc(app->storage);
    bool ok = false;
    if(storage_file_open(f, path, FSAM_READ, FSOM_OPEN_EXISTING)) {
        uint8_t head[96];
        size_t n = storage_file_read(f, head, sizeof(head));
        size_t off;
        if(spec_rks_header(head, n, &off, beg, end)) {
            uint32_t len = (uint32_t)(*end - *beg) + 1;
            if(run) spec_prepare_run(&app->m);
            storage_file_seek(f, off, true);
            uint8_t chunk[256];
            uint32_t done = 0;
            while(done < len) {
                size_t want = len - done > sizeof(chunk) ? sizeof(chunk) : len - done;
                size_t got = storage_file_read(f, chunk, want);
                if(got == 0) break;
                for(size_t i = 0; i < got; i++) spec_poke(&app->m, (uint16_t)(*beg + done + i), chunk[i]);
                done += got;
            }
            ok = done == len;
            if(run && ok) spec_start_at(&app->m, *beg);
        }
    }
    storage_file_close(f);
    storage_file_free(f);
    return ok;
}

static void pick_and_load(App* app, bool run) {
    DialogsFileBrowserOptions opt;
    dialog_file_browser_set_basic_options(&opt, ".rks", NULL);
    opt.base_path = EXT_PATH("");
    opt.hide_ext = false;
    view_port_enabled_set(app->vp, false);
    bool chosen = dialog_file_browser_show(app->dialogs, app->last_path, app->last_path, &opt);
    view_port_enabled_set(app->vp, true);
    if(!chosen) return;
    uint16_t beg = 0, end = 0;
    furi_mutex_acquire(app->mutex, FuriWaitForever);
    bool ok = load_rks_file(app, furi_string_get_cstr(app->last_path), run, &beg, &end);
    furi_mutex_release(app->mutex);
    if(ok) {
        toast(app, "%s %04X-%04X", run ? "Run" : "Loaded", beg, end);
        if(run) {
            app->mode = ModeJoy;
            app->view = SPEC_VIEW_FULL;
        }
    } else {
        toast(app, "Not a program");
    }
}

/* ---------------------------------------------------------------- keys */

static void key_release_deferred(App* app, int code) {
    /* the monitor scans the keyboard a few times per frame and debounces: keep taps >= 3 frames */
    uint32_t held = app->frame_no - app->held_frame;
    if(held >= 3) {
        spec_key_up(&app->m, code);
        return;
    }
    for(int i = 0; i < 4; i++) {
        if(app->pending[i].frames == 0) {
            app->pending[i].code = code;
            app->pending[i].frames = 3 - (int)held;
            return;
        }
    }
    spec_key_up(&app->m, code);
}

static void tick_pending(App* app) {
    for(int i = 0; i < 4; i++) {
        if(app->pending[i].frames > 0 && --app->pending[i].frames == 0) {
            spec_key_up(&app->m, app->pending[i].code);
            if(app->shift_latch && app->pending[i].code != SPEC_KEY_SHIFT) {
                app->shift_latch = false;
                spec_key_up(&app->m, SPEC_KEY_SHIFT);
            }
        }
    }
}

static void release_everything(App* app) {
    spec_release_all(&app->m);
    memset(app->pending, 0, sizeof(app->pending));
    app->held_code = -1;
    app->shift_latch = false;
    for(int i = 0; i < 4; i++) app->joy_codes[i] = -1;
}

static void set_mode(App* app, UiMode mode) {
    release_everything(app);
    app->mode = mode;
    app->followed_addr = 0;
    if(mode == ModeKbd && app->view == SPEC_VIEW_FULL) app->view = SPEC_VIEW_1X;
    clamp_view(app);
}

static void menu_activate(App* app, int item, int dir) {
    switch(item) {
    case MiKeyboard: app->menu = false; set_mode(app, ModeKbd); break;
    case MiJoystick: app->menu = false; set_mode(app, ModeJoy); break;
    case MiZoom:
        app->view = (SpecView)((app->view + SPEC_VIEW_COUNT + dir) % SPEC_VIEW_COUNT);
        clamp_view(app);
        break;
    case MiFollow: app->follow = !app->follow; app->followed_addr = 0; break;
    case MiInvert: app->invert = !app->invert; break;
    case MiLoad: app->menu = false; pick_and_load(app, false); break;
    case MiLoadRun: app->menu = false; pick_and_load(app, true); break;
    case MiSpeed: app->speed_idx = (app->speed_idx + SPEED_COUNT + dir) % SPEED_COUNT; break;
    case MiSound: app->sound = !app->sound; break;
    case MiOkKey: app->ok_key_idx = (app->ok_key_idx + OK_KEY_COUNT + dir) % OK_KEY_COUNT; break;
    case MiReset:
        furi_mutex_acquire(app->mutex, FuriWaitForever);
        spec_reset(&app->m);
        release_everything(app);
        furi_mutex_release(app->mutex);
        app->menu = false;
        break;
    case MiCold:
        furi_mutex_acquire(app->mutex, FuriWaitForever);
        spec_hard_reset(&app->m);
        release_everything(app);
        furi_mutex_release(app->mutex);
        app->menu = false;
        app->mode = ModeView;
        break;
    default: app->running = false; break;
    }
}

static void handle_input(App* app, const InputEvent* ev) {
    if(ev->key == InputKeyBack && ev->type == InputTypeLong) {
        app->running = false;
        return;
    }

    if(app->menu) {
        if(ev->type != InputTypeShort && ev->type != InputTypeRepeat) return;
        switch(ev->key) {
        case InputKeyUp: app->menu_sel = (app->menu_sel + MiCount - 1) % MiCount; break;
        case InputKeyDown: app->menu_sel = (app->menu_sel + 1) % MiCount; break;
        case InputKeyLeft: menu_activate(app, app->menu_sel, -1); break;
        case InputKeyRight: menu_activate(app, app->menu_sel, +1); break;
        case InputKeyOk: menu_activate(app, app->menu_sel, +1); break;
        case InputKeyBack: app->menu = false; break;
        default: break;
        }
        return;
    }

    switch(app->mode) {
    case ModeView: {
        if(ev->key == InputKeyBack && ev->type == InputTypeShort) {
            app->menu = true;
            release_everything(app);
            return;
        }
        if(ev->key == InputKeyOk) {
            if(ev->type == InputTypeShort) set_mode(app, ModeKbd);
            else if(ev->type == InputTypeLong) {
                static const char* NAMES[] = {"Zoom 1:1", "Zoom 1:2", "Whole screen"};
                app->view = (SpecView)((app->view + 1) % SPEC_VIEW_COUNT);
                clamp_view(app);
                toast(app, "%s", NAMES[app->view]);
            }
            return;
        }
        if(ev->type != InputTypePress && ev->type != InputTypeRepeat) return;
        int step = app->view == SPEC_VIEW_2X ? 16 : 8;
        if(ev->type == InputTypeRepeat) step *= 2;
        switch(ev->key) {
        case InputKeyUp: app->vy -= step; break;
        case InputKeyDown: app->vy += step; break;
        case InputKeyLeft: app->vx -= step; break;
        case InputKeyRight: app->vx += step; break;
        default: return;
        }
        if(app->follow) {
            app->follow = false;
            toast(app, "Follow off");
        }
        clamp_view(app);
        return;
    }
    case ModeKbd: {
        if(ev->key == InputKeyBack) {
            if(ev->type == InputTypeShort) set_mode(app, ModeView);
            return;
        }
        if(ev->key == InputKeyOk) {
            int code = kbd_code(app->kbd_r, app->kbd_c);
            if(ev->type == InputTypePress) {
                if(code == SPEC_KEY_SHIFT) {
                    app->shift_latch = !app->shift_latch;
                    if(app->shift_latch) spec_key_down(&app->m, SPEC_KEY_SHIFT);
                    else spec_key_up(&app->m, SPEC_KEY_SHIFT);
                } else {
                    spec_key_down(&app->m, code);
                    app->held_code = code;
                    app->held_frame = app->frame_no;
                }
            } else if(ev->type == InputTypeRelease && app->held_code >= 0) {
                key_release_deferred(app, app->held_code);
                if(app->shift_latch && app->frame_no - app->held_frame >= 3) {
                    app->shift_latch = false;
                    spec_key_up(&app->m, SPEC_KEY_SHIFT);
                }
                app->held_code = -1;
            }
            return;
        }
        if(ev->type != InputTypePress && ev->type != InputTypeRepeat) return;
        int n = kbd_row_len(app->kbd_r);
        switch(ev->key) {
        case InputKeyLeft: app->kbd_c = (app->kbd_c + n - 1) % n; break;
        case InputKeyRight: app->kbd_c = (app->kbd_c + 1) % n; break;
        case InputKeyUp:
        case InputKeyDown: {
            int old_n = n;
            app->kbd_r = (app->kbd_r + (ev->key == InputKeyUp ? KBD_ROWS - 1 : 1)) % KBD_ROWS;
            int nn = kbd_row_len(app->kbd_r);
            /* keep the same physical column when moving to/from the row with НР */
            if(nn > old_n) app->kbd_c++;
            else if(nn < old_n && app->kbd_c > 0) app->kbd_c--;
            if(app->kbd_c >= nn) app->kbd_c = nn - 1;
            break;
        }
        default: break;
        }
        return;
    }
    case ModeJoy: {
        if(ev->key == InputKeyBack) {
            if(ev->type == InputTypeShort) set_mode(app, ModeView);
            return;
        }
        int idx = -1, code = -1;
        switch(ev->key) {
        case InputKeyUp: idx = 0; code = SPEC_K_UP; break;
        case InputKeyDown: idx = 1; code = SPEC_K_DOWN; break;
        case InputKeyLeft: idx = 2; code = SPEC_K_LEFT; break;
        case InputKeyRight: idx = 3; code = SPEC_K_RIGHT; break;
        case InputKeyOk:
            if(ev->type == InputTypePress) {
                app->held_code = OK_KEYS[app->ok_key_idx];
                app->held_frame = app->frame_no;
                spec_key_down(&app->m, app->held_code);
            } else if(ev->type == InputTypeRelease && app->held_code >= 0) {
                key_release_deferred(app, app->held_code);
                app->held_code = -1;
            }
            return;
        default: return;
        }
        if(ev->type == InputTypePress && app->joy_codes[idx] < 0) {
            app->joy_codes[idx] = code;
            spec_key_down(&app->m, code);
        } else if(ev->type == InputTypeRelease && app->joy_codes[idx] >= 0) {
            spec_key_up(&app->m, app->joy_codes[idx]);
            app->joy_codes[idx] = -1;
        }
        return;
    }
    }
}

/* ---------------------------------------------------------------- sound */

static void update_sound(App* app, uint32_t toggles) {
    if(!app->speaker) return;
    /* toggles counted over one emulated 20 ms slice; at higher speed the slice plays faster */
    float freq = app->sound ? (float)toggles * 25.0f * SPEEDS[app->speed_idx] : 0.0f;
    if(freq < 60.0f || freq > 12000.0f) freq = 0.0f;
    if(freq == 0.0f) {
        if(app->tone != 0.0f) furi_hal_speaker_stop();
    } else if(app->tone == 0.0f || freq > app->tone * 1.03f || freq < app->tone * 0.97f) {
        furi_hal_speaker_start(freq, 0.6f);
    } else {
        freq = app->tone;
    }
    app->tone = freq;
}

/* ---------------------------------------------------------------- main */

int32_t specialist_app(void* p) {
    UNUSED(p);
    App* app = malloc(sizeof(App));
    memset(app, 0, sizeof(App));
    app->mem = malloc(0x10000);
    spec_init(&app->m, app->mem);

    app->mutex = furi_mutex_alloc(FuriMutexTypeNormal);
    app->queue = furi_message_queue_alloc(16, sizeof(InputEvent));
    app->storage = furi_record_open(RECORD_STORAGE);
    app->dialogs = furi_record_open(RECORD_DIALOGS);
    app->notif = furi_record_open(RECORD_NOTIFICATION);
    app->gui = furi_record_open(RECORD_GUI);
    app->last_path = furi_string_alloc_set(ASSETS_DIR "/games");
    storage_common_mkdir(app->storage, DATA_DIR);

    app->running = true;
    app->mode = ModeView;
    app->view = SPEC_VIEW_1X;
    app->follow = true;
    app->invert = true;
    app->speed_idx = 1;
    app->sound = true;
    app->held_code = -1;
    app->kbd_r = 0;
    app->kbd_c = 0;
    for(int i = 0; i < 4; i++) app->joy_codes[i] = -1;

    load_rom(app);

    app->vp = view_port_alloc();
    view_port_draw_callback_set(app->vp, draw_cb, app);
    view_port_input_callback_set(app->vp, input_cb, app);
    gui_add_view_port(app->gui, app->vp, GuiLayerFullscreen);
    notification_message(app->notif, &sequence_display_backlight_enforce_on);
    app->speaker = furi_hal_speaker_acquire(500);

    uint32_t next = furi_get_tick();
    uint32_t perf_t0 = next;
    while(app->running) {
        InputEvent ev;
        while(furi_message_queue_get(app->queue, &ev, 0) == FuriStatusOk) handle_input(app, &ev);
        if(!app->running) break;

        furi_mutex_acquire(app->mutex, FuriWaitForever);
        if(!app->menu && app->m.rom_loaded) {
            uint32_t t0 = furi_get_tick();
            spec_run(&app->m, (int32_t)(SPEC_FRAME_CYCLES * SPEEDS[app->speed_idx]));
            app->emu_ms_acc += furi_get_tick() - t0;
            app->emu_frames_acc++;
            app->frame_no++;
            tick_pending(app);
            follow_cursor(app);
            update_sound(app, spec_take_toggles(&app->m));
        } else {
            update_sound(app, 0);
        }
        /* the whole-screen mode is expensive to scale: refresh it at 25 Hz */
        if(app->view != SPEC_VIEW_FULL || (app->frame_no & 1) == 0 || app->menu)
            spec_render(&app->m, app->xbm, app->view, app->vx, app->vy);
        furi_mutex_release(app->mutex);
        view_port_update(app->vp);

        /* performance: % of real time the emulation needs (100% = just keeps up) */
        uint32_t now = furi_get_tick();
        if(now - perf_t0 >= 1000 && app->emu_frames_acc > 0) {
            uint32_t budget = app->emu_frames_acc * (1000 / SPEC_FPS);
            app->perf_pct = (int)(budget * 100 / (app->emu_ms_acc ? app->emu_ms_acc : 1));
            if(app->perf_pct > 999) app->perf_pct = 999;
            app->emu_ms_acc = app->emu_frames_acc = 0;
            perf_t0 = now;
        }

        next += 1000 / SPEC_FPS;
        int32_t wait = (int32_t)(next - furi_get_tick());
        if(wait > 0) {
            if(furi_message_queue_get(app->queue, &ev, (uint32_t)wait) == FuriStatusOk) {
                handle_input(app, &ev);
                wait = (int32_t)(next - furi_get_tick());
                if(wait > 0) furi_delay_ms((uint32_t)wait);
            }
        } else if(wait < -200) {
            next = furi_get_tick(); /* fell behind: don't try to catch up */
        }
    }

    if(app->speaker) {
        furi_hal_speaker_stop();
        furi_hal_speaker_release();
    }
    notification_message(app->notif, &sequence_display_backlight_enforce_auto);
    gui_remove_view_port(app->gui, app->vp);
    view_port_free(app->vp);
    furi_record_close(RECORD_GUI);
    furi_record_close(RECORD_NOTIFICATION);
    furi_record_close(RECORD_DIALOGS);
    furi_record_close(RECORD_STORAGE);
    furi_string_free(app->last_path);
    furi_message_queue_free(app->queue);
    furi_mutex_free(app->mutex);
    free(app->mem);
    free(app);
    return 0;
}
