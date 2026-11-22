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
#include "akey.h"
#include "colours.h"
#include "input.h"
#include "screen.h"
#include "sound.h"
#include "sio.h"
#include "cartridge.h"
#include "cassette.h"
#include "binload.h"
#include "gtia.h"
#include "pokey.h"
#include "sysrom.h"
#include <stdio.h>
#include <string.h>
#include <stdarg.h>
#include <stdlib.h>
#include <unistd.h>
#include <strings.h>

#define AUDIO_CAPACITY 8192

static char system_dir[400], game_path[500];
static int loaded, initialized, basic_enabled;
/* Content is a raw cartridge image (.rom .bin .a52): the core inserts it itself, with the type
 * found from its CRC/size, so it must not be opened again (see engine_open). */
static int raw_cart;
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

/* Extensions the libretro core treats as raw cartridge images (no CART header). */
static int is_raw_cart(const char *path) {
    const char *dot = strrchr(path, '.');
    if (!dot) return 0;
    return !strcasecmp(dot, ".rom") || !strcasecmp(dot, ".bin") || !strcasecmp(dot, ".a52");
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
    /* Raw cartridge images: the core already inserted them with a type from its database
     * (libretro-core.c, update_variables); opening them again here would insert them as
     * "unknown type" and the machine would wait for the user to pick one. Other content is
     * opened explicitly, as the core leaves that to the frontend. */
    raw_cart = *game_path && is_raw_cart(game_path);
    if (raw_cart && CARTRIDGE_main.type == CARTRIDGE_NONE) {
        engine_close();
        return 0;
    }
    if (*game_path && !raw_cart && AFILE_OpenFile(game_path, 1, 1, 0) == AFILE_ERROR) {
        engine_close();
        return 0;
    }
    if (Screen_atari) memset(Screen_atari, 0, Screen_WIDTH * Screen_HEIGHT);
    timeline_reset();
    return 1;
}

int engine_is_open(void) { return loaded; }

int engine_is_5200(void) { return loaded && Atari800_machine_type == Atari800_MACHINE_5200; }

/*
 * The Atari 5200 console has no keyboard and no START/SELECT/OPTION: it has a keypad with
 * START, PAUSE, RESET, 0-9, * and #. The app's controls are mapped onto it: START, SELECT and
 * OPTION = START, PAUSE, RESET; digit keys = keypad digits; * and = (also # with SHIFT) = * and #.
 * The second fire button of the 5200 joystick is SHIFT (INPUT_key_shift).
 */
static int keypad_5200(const EngineInput *in) {
    static const int digits[10][2] = {
        {AKEY_0, AKEY_5200_0}, {AKEY_1, AKEY_5200_1}, {AKEY_2, AKEY_5200_2}, {AKEY_3, AKEY_5200_3},
        {AKEY_4, AKEY_5200_4}, {AKEY_5, AKEY_5200_5}, {AKEY_6, AKEY_5200_6}, {AKEY_7, AKEY_5200_7},
        {AKEY_8, AKEY_5200_8}, {AKEY_9, AKEY_5200_9},
    };
    if (!(in->consol & INPUT_CONSOL_START)) return AKEY_5200_START;
    if (!(in->consol & INPUT_CONSOL_SELECT)) return AKEY_5200_PAUSE;
    if (!(in->consol & INPUT_CONSOL_OPTION)) return AKEY_5200_RESET;
    if (in->akey < 0) return AKEY_NONE;
    int key = in->akey & 0x3f;
    for (int i = 0; i < 10; i++) if (key == digits[i][0]) return digits[i][1];
    if (key == AKEY_ASTERISK) return AKEY_5200_ASTERISK;
    if (key == AKEY_EQUAL || in->akey == AKEY_HASH) return AKEY_5200_HASH;
    return AKEY_NONE;
}

void engine_frame_body(const EngineInput *in) {
    buttons = (unsigned)in->buttons;
    Retro_PollEvent();                   /* joystick 1 from buttons, through input() */
    if (Atari800_machine_type == Atari800_MACHINE_5200) {
        INPUT_key_code = keypad_5200(in);
        INPUT_key_shift = in->shift;
        INPUT_key_consol = INPUT_CONSOL_NONE;
    } else {
        INPUT_key_code = in->akey;
        INPUT_key_shift = in->shift;
        INPUT_key_consol = in->consol;
    }
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
    timeline_leave_frame();
    if (*game_path && !raw_cart) AFILE_OpenFile(game_path, 1, 1, 0);
    else Atari800_Coldstart(); /* a cartridge stays inserted */
    buttons = 0;
    timeline_reset();
}

/* ---- machine state: the core's save state plus what it leaves out ---- */

#define STATE_MAGIC 0x41545331 /* "ATS1" */

/* Before the core's own state, what its save state leaves out: 8 ints of input.c (keys and sticks
 * between frames), 11 of the XEX loader (binload.c, with its ESC entry), the pot scan counter
 * (pokey.c), the 4 trigger inputs of GTIA, and the boot-time console override (OPTION held to
 * switch BASIC off, START for a cassette boot; the core's state load sets it to 0) with the
 * cassette boot flags. Saves from before ATARI-008 have only the 8 (LEGACY_EXTRA). */
#define EXTRA_INTS 27
#define LEGACY_EXTRA 8

/* then the serial transfer in progress (sio.c), padded to whole ints */
#define SIO_BYTES ((SIO_ALEX_STATE_SIZE + 3) / 4 * 4)
#define EXTRA_BYTES (EXTRA_INTS * sizeof(int) + SIO_BYTES)
#define LEGACY_BYTES (LEGACY_EXTRA * sizeof(int))

size_t engine_snapshot_extra(void) { return EXTRA_BYTES; }

size_t engine_snapshot_size(void) { return loaded ? EXTRA_BYTES + retro_serialize_size() : 0; }

int engine_snapshot_save(void *data, size_t size) {
    if (!loaded || size < engine_snapshot_size()) return 0;
    int *x = (int *)data;
    INPUT_AlexGetState(x);
    BINLOAD_AlexGetState(x + 8);
    x[19] = POKEY_AlexGetPotScanline();
    for (int i = 0; i < 4; i++) x[20 + i] = GTIA_TRIG[i];
    x[24] = GTIA_consol_override;
    x[25] = CASSETTE_hold_start;
    x[26] = CASSETTE_press_space;
    memset((char *)data + EXTRA_INTS * sizeof(int), 0, SIO_BYTES);
    SIO_AlexGetState((unsigned char *)data + EXTRA_INTS * sizeof(int));
    return retro_serialize((char *)data + EXTRA_BYTES, size - EXTRA_BYTES);
}

/* extra: EXTRA_BYTES, or LEGACY_BYTES for a save from before ATARI-008 */
static int snapshot_load_extra(const void *data, size_t size, size_t extra) {
    if (!loaded || size < extra + retro_serialize_size()) return 0;
    if (!retro_unserialize((const char *)data + extra, size - extra)) return 0;
    const int *x = (const int *)data;
    INPUT_AlexSetState(x);
    if (extra >= EXTRA_BYTES) {
        BINLOAD_AlexSetState(x + 8, game_path);
        POKEY_AlexSetPotScanline(x[19]);
        for (int i = 0; i < 4; i++) GTIA_TRIG[i] = (UBYTE)x[20 + i];
        GTIA_consol_override = x[24];
        CASSETTE_hold_start = x[25];
        CASSETTE_press_space = x[26];
        SIO_AlexSetState((const unsigned char *)data + EXTRA_INTS * sizeof(int));
    } else {
        /* an old save: the program had been loaded long before */
        static const int done[11] = {-1, 0, 0, 0, 0, 0, 0, 1, 0, 0, -1};
        BINLOAD_AlexSetState(done, NULL);
    }
    return 1;
}

int engine_snapshot_load(const void *data, size_t size) { return snapshot_load_extra(data, size, EXTRA_BYTES); }

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

/* ---- saves with history (ATARI-008): "ATS2", u32 payload length, zlib(payload of timeline.c) ---- */

#define STATE2_MAGIC 0x41545332 /* "ATS2" */

/* The system zlib (-lz). Not through <zlib.h>: the include path finds libretro-common's copy
 * first, which declares the length arguments as uint32_t *, while the library writes an
 * unsigned long (8 bytes on the 64-bit phones). These are zlib's real prototypes. */
extern int compress2(unsigned char *dest, unsigned long *dest_len, const unsigned char *src,
                     unsigned long src_len, int level);
extern int uncompress(unsigned char *dest, unsigned long *dest_len, const unsigned char *src,
                      unsigned long src_len);
extern unsigned long compressBound(unsigned long src_len);
#define ZIP_OK 0
#define STATE2_MAX (256u << 20)

void *engine_save_history(size_t *size) {
    *size = 0;
    if (!loaded) return NULL;
    size_t raw_len;
    void *raw = timeline_save_payload(&raw_len);
    if (!raw) return NULL;
    unsigned long zlen = compressBound(raw_len);
    unsigned char *out = malloc(8 + zlen);
    if (!out || raw_len > STATE2_MAX || compress2(out + 8, &zlen, raw, raw_len, 6) != ZIP_OK) {
        free(raw);
        free(out);
        return NULL;
    }
    free(raw);
    uint32_t magic = STATE2_MAGIC, len = (uint32_t)raw_len;
    memcpy(out, &magic, 4);
    memcpy(out + 4, &len, 4);
    *size = 8 + zlen;
    return out;
}

static int load_history(const unsigned char *data, size_t size) {
    uint32_t len;
    if (size < 9) return 0;
    memcpy(&len, data + 4, 4);
    if (len < 16 || len > STATE2_MAX) return 0;
    unsigned char *raw = malloc(len);
    if (!raw) return 0;
    unsigned long got = len;
    int ok = uncompress(raw, &got, data + 8, size - 8) == ZIP_OK && got == len
            && timeline_load_payload(raw, len);
    free(raw);
    return ok;
}

/* Loads a save: with history ("ATS2", ATARI-008) or an older one without ("ATS1"). A damaged file
 * or one from another machine (a different snapshot size) is refused before the machine changes. */
int engine_load(const void *data, size_t size) {
    uint32_t magic;
    if (!loaded || size < 4) return 0;
    memcpy(&magic, data, 4);
    if (magic == STATE2_MAGIC) return load_history(data, size);
    if (magic != STATE_MAGIC) return 0;
    size_t extra;
    if (size == engine_state_size()) extra = EXTRA_BYTES;
    else if (size == 4 + LEGACY_BYTES + retro_serialize_size()) extra = LEGACY_BYTES;
    else return 0;
    timeline_leave_frame();
    if (!snapshot_load_extra((const char *)data + 4, size - 4, extra)) return 0;
    timeline_reset();
    return 1;
}

uint8_t *engine_memory(void) { return loaded ? retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM) : NULL; }
