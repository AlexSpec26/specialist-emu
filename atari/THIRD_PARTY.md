# Atari runtime licenses and provenance

The playable Android application in `atari/` is distributed under **GPL-2.0-or-later**.
This license applies to this module and its native frontend; no license change is
made to other repository products. Full GPL text: `atari/COPYING`.

## Atari800 / libretro

Source: https://github.com/libretro/libretro-atari800
Pinned revision: `4e7fbc73765c1a9670c7506616046ad1d4ccda51`.
The complete pinned source tree is included in `atari/src/main/jni/vendor/atari800/`.
Atari800 copyright: Atari800 Development Team; see vendor `atari800/DOC/CREDITS`,
individual file headers and `atari800/COPYING`. Libretro-common files retain their
individual permissive license notices. Bundled zlib retains its original notices;
Android and host builds link platform zlib.

Local modifications to vendored source:
- `libretro/cmdline.c`: reset PARAMCOUNT before each load and remove an out-of-bounds
  post-init argv write on no-content boot, clear argument parsing state per load,
  pass explicitly imported firmware paths, propagate initialization failures.
- `libretro/libretro-core.c`: preserve the system-directory string without a trailing
  literal zero character and propagate emulator initialization failure.
- `libretro/platform.c`: disable direct-mouse mode for the joystick/keyboard frontend;
  otherwise a second initialization rejects its own stale mouse setting.
- `atari800/src/cpu.c` (only with `-DALEXSPEC_HOOKS`, set by this app's builds): an
  instruction hook before every opcode fetch and traced memory-access macros, used for the
  app's memory map, single steps and slow motion (see `src/main/jni/native/alexspec_hooks.h`
  and `timeline.c`); the MONITOR_BREAK execution history reads memory without tracing.
- `atari800/src/input.c`: the key/joystick state that `INPUT_Frame` keeps between frames is
  moved from function-static to file-static variables with `INPUT_AlexGetState` /
  `INPUT_AlexSetState`, so replays from snapshots are exact.

The Atari frontend (engine.c, timeline.c, jni.c), the Android application and the tests are
original additions; the Android screens are ported from this repository's ZX Spectrum app. The release
includes a corresponding-source archive, build files, upstream source and notices.
Build instructions are in `atari/README.md`. No binary-only core downloads are used.

## Freely redistributable replacement firmware

Altirra kernel ROM replacement 3.11, copyright (C) 2008–2018 Avery Lee;
Altirra BASIC replacement, copyright (C) 2008–2022 Avery Lee. These are the original
upstream replacement ROMs compiled from source, **not proprietary Atari ROMs**.
The exact notices in `vendor/atari800/atari800/src/roms/altirra*.c` are preserved:

> Copying and distribution of this file, with or without modification,
> are permitted in any medium without royalty provided the copyright
> notice and this notice are preserved. This file is offered as-is,
> without any warranty.

## Catch the Star demo

Original game and generator: `atari/tools/make_demo.py`,
`atari/src/main/assets/demos/catch-the-star.xex`.
Dedicated to the public domain under CC0-1.0. To the extent possible under law,
its authors waive all copyright and related rights in this game and generator.
https://creativecommons.org/publicdomain/zero/1.0/

No third-party commercial games or original Atari OS/BASIC ROMs are included.
