# DOS 286: licenses and provenance

The Android application in `dos/` is distributed under **GPL-2.0-or-later** (full text:
`dos/COPYING`), because it links the DOSBox Pure core. No other product of this repository
changes its license.

## DOSBox Pure

Source: https://github.com/libretro/dosbox-pure (libretro's mirror; the author's repository has
moved to https://codeberg.org/schelling/dosbox-pure).
Pinned revision: `e39497e7c2a32c67c26e6598dd14f6d58c384bfd` (version 1.0-preview8, 2026-11-06).
The complete pinned source tree is in `dos/src/main/jni/vendor/dosbox-pure/`, unmodified.
DOSBox Pure: Copyright (C) 2020-2026 Bernhard Schelling; DOSBox: Copyright (C) 2002-2021 The
DOSBox Team — see `DOSBOX-AUTHORS`, `DOSBOX-THANKS`, `LICENSE` and the file headers there.
Bundled libretro-common files keep their own permissive notices.

Each release publishes `dos-source-N.tar.gz`: the exact sources of that build.

## Test program

`dos/tools/demo.asm` / `DEMO.COM` (72 bytes) were written for this project's tests.
