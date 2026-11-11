/*
 * DOS PC engine: a minimal libretro frontend around the DOSBox Pure core, plus the history
 * ring for rewinding. Everything is called from one thread (the app's emulation thread).
 * GPL-2.0-or-later, see dos/COPYING.
 */
#include "engine.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "libretro.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "DOS", __VA_ARGS__)
#else
#define LOGW(...) fprintf(stderr, __VA_ARGS__)
#endif

/* ------------------------------------------------------------------ options */

#define MAX_OPTIONS 48
static struct { char *key, *value; } options[MAX_OPTIONS];
static int option_count, options_changed;

static const char *option(const char *key) {
    for (int i = 0; i < option_count; i++)
        if (!strcmp(options[i].key, key)) return options[i].value;
    return NULL;
}

void engine_set_option(const char *key, const char *value) {
    for (int i = 0; i < option_count; i++) {
        if (!strcmp(options[i].key, key)) {
            if (strcmp(options[i].value, value)) {
                free(options[i].value);
                options[i].value = strdup(value);
                options_changed = 1;
            }
            return;
        }
    }
    if (option_count == MAX_OPTIONS) return;
    options[option_count].key = strdup(key);
    options[option_count].value = strdup(value);
    option_count++;
    options_changed = 1;
}

static void default_option(const char *key, const char *value) {
    if (!option(key)) engine_set_option(key, value);
}

/* ------------------------------------------------------------------ frontend state */

static char *system_dir, *save_dir;
static int is_open;
static int pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;
static struct retro_system_av_info av;

static uint32_t *picture;
static int pic_w, pic_h;

static int16_t *audio_out;
static int audio_cap, audio_len;

static uint8_t keys[RETROK_LAST];
/* the core takes keys as events through this callback (its menu and DOS alike); keys[] is also
 * polled, to tell which keys are still held */
static retro_keyboard_event_t keyboard_event;
static int pointer_x, pointer_y, pointer_pressed, pointer_count;
static long long frames;

static void core_log(enum retro_log_level level, const char *fmt, ...) {
    if (level < RETRO_LOG_WARN) return;
    char buf[512];
    va_list a;
    va_start(a, fmt);
    vsnprintf(buf, sizeof buf, fmt, a);
    va_end(a);
    LOGW("%s", buf);
}

static bool environment(unsigned cmd, void *data) {
    switch (cmd) {
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        *(const char **)data = system_dir;
        return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *(const char **)data = save_dir;
        return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
        int f = *(const int *)data;
        if (f != RETRO_PIXEL_FORMAT_XRGB8888 && f != RETRO_PIXEL_FORMAT_RGB565) return false;
        pixel_format = f;
        return true;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        struct retro_variable *v = data;
        v->value = option(v->key);
        return v->value != NULL;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *(bool *)data = options_changed != 0;
        options_changed = 0;
        return true;
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        ((struct retro_log_callback *)data)->log = core_log;
        return true;
    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
        *(unsigned *)data = 0; /* plain variables: values come from GET_VARIABLE */
        return true;
    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
        av = *(const struct retro_system_av_info *)data;
        return true;
    case RETRO_ENVIRONMENT_SET_GEOMETRY:
        av.geometry = *(const struct retro_game_geometry *)data;
        return true;
    case RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK:
        keyboard_event = ((const struct retro_keyboard_callback *)data)->callback;
        return true;
    case RETRO_ENVIRONMENT_SET_VARIABLES:
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
    case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
        return true;
    default:
        return false;
    }
}

static void video(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data || data == RETRO_HW_FRAME_BUFFER_VALID) return; /* a repeated frame */
    if (w > ENGINE_MAX_W) w = ENGINE_MAX_W;
    if (h > ENGINE_MAX_H) h = ENGINE_MAX_H;
    for (unsigned y = 0; y < h; y++) {
        uint32_t *out = picture + (size_t)y * w;
        if (pixel_format == RETRO_PIXEL_FORMAT_XRGB8888) {
            const uint32_t *in = (const uint32_t *)((const uint8_t *)data + y * pitch);
            for (unsigned x = 0; x < w; x++) out[x] = in[x] | 0xFF000000u;
        } else {
            const uint16_t *in = (const uint16_t *)((const uint8_t *)data + y * pitch);
            for (unsigned x = 0; x < w; x++) {
                uint32_t p = in[x];
                uint32_t r = (p >> 11) & 31, g = (p >> 5) & 63, b = p & 31;
                out[x] = 0xFF000000u | (r << 19 | r << 14) | (g << 10 | g << 4) | (b << 3 | b >> 2);
            }
        }
    }
    pic_w = (int)w;
    pic_h = (int)h;
}

static size_t audio_batch(const int16_t *data, size_t count) {
    size_t n = count * 2;
    if (audio_out) {
        size_t room = (size_t)(audio_cap - audio_len);
        size_t take = n < room ? n : room;
        memcpy(audio_out + audio_len, data, take * sizeof(int16_t));
        audio_len += (int)take;
    }
    return count;
}

static void audio_sample(int16_t l, int16_t r) {
    int16_t s[2] = {l, r};
    audio_batch(s, 1);
}

static void input_poll(void) {
}

static int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    (void)index;
    if (port != 0) return 0;
    switch (device) {
    case RETRO_DEVICE_KEYBOARD:
        return id < RETROK_LAST ? keys[id] : 0;
    case RETRO_DEVICE_POINTER:
        switch (id) {
        case RETRO_DEVICE_ID_POINTER_X: return (int16_t)pointer_x;
        case RETRO_DEVICE_ID_POINTER_Y: return (int16_t)pointer_y;
        case RETRO_DEVICE_ID_POINTER_PRESSED: return (int16_t)pointer_pressed;
        case RETRO_DEVICE_ID_POINTER_COUNT: return (int16_t)pointer_count;
        default: return 0;
        }
    default:
        return 0;
    }
}

/* ------------------------------------------------------------------ history ring */

#define RING 28                         /* 7 seconds at 4 snapshots per second */
#define RING_BYTES (160u * 1024 * 1024) /* never more than this for the whole ring */
typedef struct {
    void *data;
    size_t size, cap;
    long long frame;
} Snap;
static Snap ring[RING];
static int ring_first, ring_count;
static long long next_snap;
static size_t state_size_cached;

static int snap_every(void) {
    double fps = engine_fps();
    int n = (int)(fps / 4 + 0.5);
    return n < 1 ? 1 : n;
}

static size_t ring_bytes(void) {
    size_t t = 0;
    for (int i = 0; i < RING; i++) t += ring[i].cap;
    return t;
}

static int take_snapshot(void) {
    size_t size = retro_serialize_size();
    if (!size) return 0;
    if (ring_count == RING) { /* drop the oldest */
        ring_first = (ring_first + 1) % RING;
        ring_count--;
    }
    Snap *s = &ring[(ring_first + ring_count) % RING];
    if (s->cap < size) {
        void *p = realloc(s->data, size);
        if (!p) return 0;
        s->data = p;
        s->cap = size;
    }
    if (!retro_serialize(s->data, size)) return 0;
    s->size = size;
    s->frame = frames;
    ring_count++;
    while (ring_count > 2 && ring_bytes() > RING_BYTES) {
        Snap *o = &ring[ring_first];
        free(o->data);
        o->data = NULL;
        o->cap = o->size = 0;
        ring_first = (ring_first + 1) % RING;
        ring_count--;
    }
    return 1;
}

void engine_history_clear(void) {
    ring_first = ring_count = 0;
    next_snap = frames;
}

double engine_history_seconds(void) {
    if (!ring_count) return 0;
    return (frames - ring[ring_first].frame) / engine_fps();
}

double engine_rewind(double seconds) {
    if (!is_open || !ring_count) return 0;
    long long target = frames - (long long)(seconds * engine_fps() + 0.5);
    int pick = -1;
    for (int i = ring_count - 1; i >= 0; i--) {
        Snap *s = &ring[(ring_first + i) % RING];
        if (s->frame <= target) {
            pick = i;
            break;
        }
    }
    if (pick < 0) pick = 0; /* not that far: the oldest one */
    Snap *s = &ring[(ring_first + pick) % RING];
    if (s->frame >= frames) return 0;
    if (!retro_unserialize(s->data, s->size)) return 0;
    double went = (frames - s->frame) / engine_fps();
    frames = s->frame;
    ring_count = pick + 1; /* the future is gone; the restored point stays as the newest */
    next_snap = frames + snap_every();
    engine_release_keys();
    return went;
}

/* ------------------------------------------------------------------ machine */

int engine_open(const char *sysdir, const char *savedir, const char *content) {
    if (is_open) engine_close();
    free(system_dir);
    free(save_dir);
    system_dir = strdup(sysdir ? sysdir : ".");
    save_dir = strdup(savedir ? savedir : system_dir);
    if (!picture) picture = calloc((size_t)ENGINE_MAX_W * ENGINE_MAX_H, sizeof(uint32_t));
    if (!picture) return 0;

    /* a 286 at 12.5 MHz; touch pad mouse; menu to pick the program of a zip */
    default_option("dosbox_pure_savestate", "on");
    default_option("dosbox_pure_cycles", "2750");
    default_option("dosbox_pure_memory_size", "16");
    default_option("dosbox_pure_mouse_input", "pad");
    default_option("dosbox_pure_menu_time", "99");
    default_option("dosbox_pure_on_screen_keyboard", "false");
    default_option("dosbox_pure_audiorate", "48000");
    default_option("dosbox_pure_perfstats", "none");
    options_changed = 0;

    memset(keys, 0, sizeof keys);
    keyboard_event = NULL;
    pointer_x = pointer_y = pointer_pressed = pointer_count = 0;
    pic_w = pic_h = 0;
    frames = 0;
    state_size_cached = 0;

    retro_set_environment(environment);
    retro_set_video_refresh(video);
    retro_set_audio_sample(audio_sample);
    retro_set_audio_sample_batch(audio_batch);
    retro_set_input_poll(input_poll);
    retro_set_input_state(input_state);
    retro_init();
    struct retro_game_info info = {content && *content ? content : NULL, NULL, 0, NULL};
    if (!retro_load_game(content && *content ? &info : NULL)) {
        retro_deinit();
        return 0;
    }
    retro_get_system_av_info(&av);
    is_open = 1;
    engine_history_clear();
    return 1;
}

void engine_close(void) {
    if (!is_open) return;
    retro_unload_game();
    retro_deinit();
    is_open = 0;
    engine_history_clear();
}

int engine_is_open(void) { return is_open; }

int engine_frame(int16_t *audio, int cap) {
    if (!is_open) return 0;
    if (frames >= next_snap) {
        /* no snapshot in the start menu (the core refuses while no program runs): try again
         * in two seconds */
        next_snap = frames + (take_snapshot() ? snap_every() : (long long)(2 * engine_fps()));
    }
    audio_out = audio;
    audio_cap = cap;
    audio_len = 0;
    retro_run();
    audio_out = NULL;
    frames++;
    return audio_len;
}

double engine_fps(void) { return av.timing.fps > 1 ? av.timing.fps : 60.0; }
int engine_sample_rate(void) { return av.timing.sample_rate > 1000 ? (int)(av.timing.sample_rate + 0.5) : 48000; }
long long engine_frames(void) { return frames; }
int engine_width(void) { return pic_w; }
int engine_height(void) { return pic_h; }

double engine_aspect(void) {
    if (av.geometry.aspect_ratio > 0.1f) return av.geometry.aspect_ratio;
    return 4.0 / 3.0;
}

int engine_picture(uint32_t *argb, int cap) {
    int n = pic_w * pic_h;
    if (!n || cap < n) return 0;
    memcpy(argb, picture, (size_t)n * sizeof(uint32_t));
    return n;
}

static uint16_t modifiers(void) {
    uint16_t m = 0;
    if (keys[RETROK_LSHIFT] || keys[RETROK_RSHIFT]) m |= RETROKMOD_SHIFT;
    if (keys[RETROK_LCTRL] || keys[RETROK_RCTRL]) m |= RETROKMOD_CTRL;
    if (keys[RETROK_LALT] || keys[RETROK_RALT]) m |= RETROKMOD_ALT;
    return m;
}

void engine_key(int retrok, int down) {
    if (retrok <= 0 || retrok >= RETROK_LAST) return;
    down = down ? 1 : 0;
    if (keys[retrok] == down) return;
    keys[retrok] = (uint8_t)down;
    uint32_t ch = (retrok >= 32 && retrok < 127) ? (uint32_t)retrok : 0;
    if (keyboard_event) keyboard_event(down != 0, (unsigned)retrok, ch, modifiers());
}

void engine_release_keys(void) {
    for (int k = 1; k < RETROK_LAST; k++)
        if (keys[k]) engine_key(k, 0);
    pointer_pressed = pointer_count = 0;
}

void engine_pointer(int x, int y, int pressed, int count) {
    pointer_x = x;
    pointer_y = y;
    pointer_pressed = pressed;
    pointer_count = count;
}

size_t engine_state_size(void) {
    if (!is_open) return 0;
    return retro_serialize_size();
}

int engine_save(void *data, size_t size) {
    return is_open && retro_serialize(data, size);
}

int engine_load(const void *data, size_t size) {
    if (!is_open || !retro_unserialize(data, size)) return 0;
    engine_release_keys();
    engine_history_clear();
    return 1;
}
