/*
 * Android/desktop frontend for the Atari800 libretro core (vendor/atari800).
 *
 * The core expects a libretro frontend: it asks for options and directories through
 * environment() and delivers sound through audio_batch(). This file is that frontend,
 * reduced to what the app needs: PAL 800XL/65XE with 64 KB and joystick 1. Frames are run
 * by timeline.c through engine_frame_body(); keys are given to the machine directly as
 * Atari key codes, not through the core's PC-keyboard mapping. The picture is converted
 * from Screen_atari on request.
 *
 * GPL-2.0-or-later, see atari/COPYING.
 */
#include "engine.h"
#include "engine_internal.h"
#include "libretro.h"
#include "atari.h"
#include "afile.h"
#include "colours.h"
#include "input.h"
#include "screen.h"
#include "sound.h"
#include "sio.h"
#include "cartridge.h"
#include "cassette.h"
#include "binload.h"
#include "sysrom.h"
#include <stdio.h>
#include <string.h>
#include <stdarg.h>
#include <stdlib.h>
#include <unistd.h>

#define AUDIO_CAPACITY 8192

static char system_dir[400], game_path[500];
static int loaded, initialized, basic_enabled;
static unsigned buttons;
static double fps = 49.8607597;
static size_t (*audio_sink)(const int16_t *data, size_t count);

/* Defaults the core declares (RETRO_ENVIRONMENT_SET_VARIABLES), used for options we don't fix. */
static struct { char key[128], value[256]; } options[128];
static int option_count;

/* Globals and functions of the libretro glue in vendor/atari800/libretro. */
extern char RPATH[512];
extern int pauseg, SHOWKEY, PARAMCOUNT, retro_sound_finalized;
int Retro_PollEvent(void);
void retro_sound_update(void);
/* vendor/atari800/atari800/src/input.c (specialist-emu addition) */
void INPUT_AlexGetState(int out[8]);
void INPUT_AlexSetState(const int in[8]);

static void logger(enum retro_log_level level, const char *format, ...) {
    (void)level;
    va_list ap;
    va_start(ap, format);
    vfprintf(stderr, format, ap);
    va_end(ap);
}

/* Remembers the first (default) value of each option the core declares. */
static void remember_defaults(const struct retro_variable *v) {
    option_count = 0;
    for (; v && v->key && option_count < 128; v++) {
        /* format: "Description; default|other|..." */
        const char *value = strchr(v->value, ';');
        if (!value) continue;
        value++;
        while (*value == ' ') value++;
        size_t len = strcspn(value, "|");
        if (len > 255) len = 255;
        snprintf(options[option_count].key, sizeof(options[0].key), "%s", v->key);
        memcpy(options[option_count].value, value, len);
        options[option_count].value[len] = 0;
        option_count++;
    }
}

/* The machine configuration the app always uses; everything else keeps the core default. */
static const char *fixed_option(const char *key) {
    if (!strcmp(key, "atari800_system")) return "800XL (64K)";
    if (!strcmp(key, "atari800_ntscpal")) return "PAL";
    if (!strcmp(key, "atari800_resolution")) return "336x240";
    if (!strcmp(key, "atari800_internalbasic")) return basic_enabled ? "enabled" : "disabled";
    if (!strcmp(key, "atari800_keyboard")) return "poll";
    if (!strcmp(key, "atari800_vkbd_enabled")) return "disabled"; /* the app draws its own keyboard */
    if (!strcmp(key, "atari800_sioaccel")) return "enabled";      /* fast disk loading */
    if (!strcmp(key, "atari800_opt2")) return "disabled";
    if (!strcmp(key, "paddle_active")) return "disabled";
    if (!strcmp(key, "atari800_cfg")) return "disabled";           /* no atari800.cfg file */
    if (!strcmp(key, "atari800_pokey_stereo")) return "disabled";
    return NULL;
}

static bool environment(unsigned cmd, void *data) {
    switch (cmd) {
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_CONTENT_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *(const char **)data = system_dir;
        return true;
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        ((struct retro_log_callback *)data)->log = logger;
        return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
        return *(enum retro_pixel_format *)data == RETRO_PIXEL_FORMAT_RGB565;
    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
        *(unsigned *)data = 0; /* the core then uses SET_VARIABLES */
        return true;
    case RETRO_ENVIRONMENT_SET_VARIABLES:
        remember_defaults(data);
        return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        struct retro_variable *v = data;
        v->value = fixed_option(v->key);
        for (int i = 0; !v->value && i < option_count; i++)
            if (!strcmp(v->key, options[i].key)) v->value = options[i].value;
        return v->value != NULL;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *(bool *)data = false;
        return true;
    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
        fps = ((struct retro_system_av_info *)data)->timing.fps;
        return true;
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK:
    case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE:
    case RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE:
    case RETRO_ENVIRONMENT_SET_GEOMETRY:
    case RETRO_ENVIRONMENT_SET_MESSAGE:
        return true; /* accepted and ignored */
    default:
        return false;
    }
}

/* The core's own picture output is not used: engine_picture() converts Screen_atari. */
static void video(const void *data, unsigned width, unsigned height, size_t pitch) {
    (void)data; (void)width; (void)height; (void)pitch;
}

static size_t audio_batch(const int16_t *data, size_t frames) {
    if (audio_sink) audio_sink(data, frames * 2);
    return frames;
}

void engine_set_audio_sink(size_t (*sink)(const int16_t *data, size_t count)) { audio_sink = sink; }

static void audio_sample(int16_t l, int16_t r) {
    int16_t a[2] = {l, r};
    audio_batch(a, 1);
}

static void poll(void) {}

static int16_t input(unsigned port, unsigned device, unsigned index, unsigned id) {
    (void)index;
    if (port > 0) return 0;
    device &= RETRO_DEVICE_MASK;
    if (device == RETRO_DEVICE_JOYPAD && id < 16) return (buttons >> id) & 1;
    return 0; /* keys go to the machine directly, see engine_frame_body */
}

void engine_close(void) {
    if (loaded) {
        /* Not Atari800_Exit(): it removes device/ESC hooks that the libretro init does not
         * install again, so the next engine_open would get a broken machine. Release the
         * media by hand instead. */
        for (int disk = 1; disk <= 8; disk++) SIO_Dismount(disk);
        CARTRIDGE_Remove();
        CASSETTE_Remove();
        if (BINLOAD_bin_file) {
            fclose(BINLOAD_bin_file);
            BINLOAD_bin_file = NULL;
        }
        BINLOAD_start_binloading = 0;
        retro_unload_game();
        loaded = 0;
    }
    if (initialized) {
        retro_deinit();
        initialized = 0;
    }
    buttons = 0;
    timeline_free();
}

int engine_open(const char *directory, const char *content, int basic) {
    if (!directory || strlen(directory) >= sizeof(system_dir) - 2) return 0;
    if (content && strlen(content) >= sizeof(game_path)) return 0;
    if (content && *content && AFILE_DetectFileType(content) == AFILE_ERROR) return 0;
    engine_close();

    /* Forget firmware files the user has removed or replaced since the last start. */
    for (int i = 0; i < SYSROM_LOADABLE_SIZE; i++)
        if (SYSROM_roms[i].filename && access(SYSROM_roms[i].filename, R_OK) != 0)
            SYSROM_roms[i].filename[0] = 0;

    snprintf(system_dir, sizeof(system_dir), "%s", directory);
    snprintf(game_path, sizeof(game_path), "%s", content ? content : "");
    basic_enabled = basic;
    option_count = 0;
    PARAMCOUNT = 0;

    retro_set_environment(environment);
    retro_set_video_refresh(video);
    retro_set_audio_sample(audio_sample);
    retro_set_audio_sample_batch(audio_batch);
    retro_set_input_poll(poll);
    retro_set_input_state(input);
    Sound_enabled = 1; /* Sound_Exit turns it off; each new machine needs its buffers again */
    retro_init();
    initialized = 1;

    snprintf(RPATH, sizeof(RPATH), "%s", *game_path ? game_path : "prg");
    struct retro_game_info game = {game_path, NULL, 0, NULL};
    if (!retro_load_game(*game_path ? &game : NULL)) {
        engine_close();
        return 0;
    }
    loaded = 1;
    pauseg = 0;
    SHOWKEY = -1;

    struct retro_system_av_info info;
    retro_get_system_av_info(&info);
    fps = info.timing.fps;

    /* The sound ring buffer was released by the previous machine; create it again. */
    if (!Sound_Setup()) {
        engine_close();
        return 0;
    }
    Sound_Continue();
    if (*game_path && AFILE_OpenFile(game_path, 1, 1, 0) == AFILE_ERROR) {
        engine_close();
        return 0;
    }
    if (Screen_atari) memset(Screen_atari, 0, Screen_WIDTH * Screen_HEIGHT);
    timeline_reset();
    return 1;
}

int engine_is_open(void) { return loaded; }

void engine_frame_body(const EngineInput *in) {
    buttons = (unsigned)in->buttons;
    Retro_PollEvent();                   /* joystick 1 from buttons, through input() */
    INPUT_key_code = in->akey;
    INPUT_key_shift = in->shift;
    INPUT_key_consol = in->consol;
    Atari800_Frame();
    if (retro_sound_finalized) retro_sound_update();
}

void engine_picture(uint32_t *argb) {
    const UBYTE *src = (const UBYTE *)Screen_atari;
    int x0 = (Screen_WIDTH - ENGINE_WIDTH) / 2;
    if (!src) {
        memset(argb, 0, ENGINE_WIDTH * ENGINE_HEIGHT * sizeof(uint32_t));
        return;
    }
    for (int y = 0; y < ENGINE_HEIGHT; y++) {
        const UBYTE *row = src + y * Screen_WIDTH + x0;
        for (int x = 0; x < ENGINE_WIDTH; x++)
            argb[y * ENGINE_WIDTH + x] = 0xff000000u | (uint32_t)Colours_table[row[x]];
    }
}

double engine_fps(void) { return fps; }

void engine_reset(void) {
    if (!loaded) return;
    if (*game_path) AFILE_OpenFile(game_path, 1, 1, 0);
    else Atari800_Coldstart();
    buttons = 0;
    timeline_reset();
}

/* ---- machine state: the core's save state plus what it leaves out ---- */

#define STATE_MAGIC 0x41545331 /* "ATS1" */

size_t engine_snapshot_size(void) { return loaded ? 8 * sizeof(int) + retro_serialize_size() : 0; }

int engine_snapshot_save(void *data, size_t size) {
    if (!loaded || size < engine_snapshot_size()) return 0;
    INPUT_AlexGetState((int *)data);
    return retro_serialize((char *)data + 8 * sizeof(int), size - 8 * sizeof(int));
}

int engine_snapshot_load(const void *data, size_t size) {
    if (!loaded || size < engine_snapshot_size()) return 0;
    if (!retro_unserialize((const char *)data + 8 * sizeof(int), size - 8 * sizeof(int))) return 0;
    INPUT_AlexSetState((const int *)data);
    return 1;
}

size_t engine_state_size(void) { return loaded ? 4 + engine_snapshot_size() : 0; }

int engine_save(void *data, size_t size) {
    if (!loaded || size < engine_state_size()) return 0;
    uint32_t magic = STATE_MAGIC;
    memcpy(data, &magic, 4);
    const void *frame_start = timeline_mid_frame_snapshot();
    if (frame_start) {
        memcpy((char *)data + 4, frame_start, engine_snapshot_size());
        return 1;
    }
    return engine_snapshot_save((char *)data + 4, size - 4);
}

int engine_load(const void *data, size_t size) {
    uint32_t magic;
    if (!loaded || size != engine_state_size()) return 0;
    memcpy(&magic, data, 4);
    if (magic != STATE_MAGIC || !engine_snapshot_load((const char *)data + 4, size - 4)) return 0;
    timeline_reset();
    return 1;
}

uint8_t *engine_memory(void) { return loaded ? retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM) : NULL; }
