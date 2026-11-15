# DOS 286: licenses and provenance

The Android application in `dos/` is distributed under **GPL-2.0-or-later** (full text:
`dos/COPYING`), because it links the DOSBox Pure core. No other product of this repository
changes its license.

## DOSBox Pure

Source: https://github.com/libretro/dosbox-pure (libretro's mirror; the author's repository has
moved to https://codeberg.org/schelling/dosbox-pure).
Pinned revision: `e39497e7c2a32c67c26e6598dd14f6d58c384bfd` (version 1.0-preview8, 2026-11-06).
The complete pinned source tree is in `dos/src/main/jni/vendor/dosbox-pure/`, with one change
(marked `//ALEXSPEC` in the file; GPL-2.0 §2: modified files carry a notice of the change and its date):

- `src/hardware/cmos.cpp` (2026-11-16, ADR-029): the real-time clock (ports 70h/71h, INT 1Ah
  AH=02/04) runs on emulated time — it starts from the host clock when the machine starts and then
  advances with `PIC_FullIndex()`, so it slows down with the app's slow motion and goes back with
  its rewind. `alexspec_rtc_get_base` / `alexspec_rtc_set_base` let the app keep the start in its
  saved states. Upstream reads the host clock (`time(NULL)`) on every access.
DOSBox Pure: Copyright (C) 2020-2026 Bernhard Schelling; DOSBox: Copyright (C) 2002-2021 The
DOSBox Team — see `DOSBOX-AUTHORS`, `DOSBOX-THANKS`, `LICENSE` and the file headers there.
Bundled libretro-common files keep their own permissive notices.

Each release publishes `dos-source-N.tar.gz`: the exact sources of that build.

## Test program

`dos/tools/demo.asm` / `DEMO.COM` (72 bytes) and `dos/tools/rtc.asm` / `RTC.COM` were written for
this project's tests.
