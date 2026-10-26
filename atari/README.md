# Atari 65XE Android emulator — development

Separate application ID: `com.alexspec.atari`. Branch: `feature/atari-65xe`, PR #3.

## Current milestone (ATARI-001)
- All 151 documented NMOS 6502 opcodes, binary/decimal arithmetic, IRQ/NMI/BRK,
  stack, page-cross/branch timing and the NMOS indirect-JMP wrap behaviour.
- CPU connected to the 64 KiB bus; corrected OS ROM polarity, BASIC and self-test
  overlays, mirrored PIA PORTB control/data-direction registers, ROM write protection.
- Load your own 16 KiB OS and optional 8 KiB BASIC ROM through Android's file picker.
  Images are copied to private app storage and restored on the next launch.
- CPU registers, code bytes and memory pages; reset, +1 instruction, +100 instructions,
  page navigation and jump to PC. Work runs outside the UI thread.
- No ROM images or games are bundled.

**Not yet capable of running Atari games or displaying the Atari video output.**
ANTIC, GTIA, POKEY, DMA/RDY, keyboard/joystick, disk/cassette/cartridge formats,
continuous real-time execution and save states remain to be implemented.
Hardware register arrays outside the PIA banking subset are placeholders.
CPU execution is instruction based; DMA and exact interrupt-edge bus timing are
not yet modelled. Undocumented opcodes stop explicitly at their address.
Reset currently resets CPU/PIA preserving RAM, not full Atari RESET-key behaviour.

## Verification

```sh
javac -d /tmp/atari-core atari/src/main/java/com/alexspec/atari/core/*.java tools/AtariCoreTest.java
java -cp /tmp/atari-core AtariCoreTest
# Optional external Klaus test binary (not distributed here):
java -cp /tmp/atari-core AtariCoreTest /path/to/6502_functional_test.bin
gradle :atari:assembleDebug
```

Local/CI tests: 262144 binary ADC/SBC cases, 40000 valid BCD cases, NMOS decimal
flags, branch/page/stack/interrupt/banking cases. Klaus functional test passes at
$3469 after 30646177 instructions (96241367 clocks). External test suite:
https://github.com/Klaus2m5/6502_65C02_functional_tests (GPL-3.0, fetched by CI at
7954e2dbb49c469ea286070bf46cdd71aeb29e4b; binaries not bundled in the app).

## APK

[Signed development APK, build 1](https://github.com/AlexSpec26/specialist-emu/releases/download/atari-build-1/atari-65xe-1.apk).
This is a CPU/memory inspector for development, not a playable emulator yet.

`.github/workflows/atari.yml` checks only Atari paths/tests. Trusted pushes to
`feature/atari-65xe` or `main`, and manual runs, publish a signed prerelease
`atari-build-N` using existing signing secrets. PR runs only test/build without
signing or release. Other products and their release workflows are untouched.

## Next milestones
1. Implement ANTIC display lists/DMA and GTIA output, test OS boot with Atari video.
2. Add POKEY sound, timers/IRQ, keyboard and console/joystick input.
3. Implement XEX, then ATR/CAR loading; measure game compatibility.
4. Add speed controls, memory/register visualization and history.
5. Bundle games only with verified redistribution rights and license notices.

On a physical Android device: not tested.
