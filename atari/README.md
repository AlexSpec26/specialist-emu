# Atari 65XE Android emulator — development

Separate Android application ID: `com.alexspec.atari`.

## Status
First implementation slice: Android launcher, inspectable 64 KiB address
space, RAM/ROM overlay scaffold, PIA PORTB banking placeholder, hex viewer.

**Not yet an Atari emulator capable of running games.** CPU 6502C,
ANTIC, GTIA, POKEY, input, disk/cassette/cartridge formats, timing and
save states remain to be implemented and verified. Hardware registers
currently return placeholder values. ROM images are not distributed.

## Next milestones
1. Implement and test complete 6502 instruction set and interrupt timing.
2. Implement real 65XE memory map and OS/BASIC ROM loading.
3. Implement ANTIC display lists, DMA, GTIA pixel generation and POKEY.
4. Implement START/SELECT/OPTION/RESET, joystick, XEX/ATR/CAR.
5. Add interactive memory/register map, speed controls, stepping/history.
6. Bundle only games with verified redistribution rights and license notices.
7. Add isolated CI build and signed APK release using existing signing secrets.

This module intentionally does not modify `app/`, `zx/`, or `shell/`.
