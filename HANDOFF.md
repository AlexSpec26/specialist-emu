# Current handoff
- Updated at (UTC): 2026-10-26
- Outgoing agent: ChatGPT-Codex
- Branch: feature/atari-65xe
- Last pushed runtime commit: a9e651c; последующие cleanup/docs — `git log -1`
- Open PR: https://github.com/AlexSpec26/specialist-emu/pull/3
- Active backlog IDs: ATARI-002 — Review; ATARI-001 сохранён как учебный режим.
- User-approved goal: «продолжай до получения полноценного apk».

## What changed and why
- Игровой режим на Atari800 7.0.0/libretro: ANTIC/GTIA/DMA, POKEY, XEX/ATR/CAR/CAS,
  PAL 64K XL/XE, свободные замены Altirra OS/BASIC, импорт собственных OS/BASIC.
- Экран, звук, multitouch джойстик/FIRE/консоль/клавиатура, physical keyboard/gamepad,
  библиотека, пауза/сброс, quick/auto saves с профилем программы/ПЗУ и checksum.
- Оригинальная CC0 игра «Поймай звезду» + генератор включены для немедленного запуска.
- Учебное Java M6502/core осталось в отдельном InspectorActivity; его тесты сохранены.
- Native source полностью включён в atari/src/main/jni/vendor; GPL-2.0-or-later
  для игрового Atari-модуля, сохранены лицензии/уведомления и точный upstream revision.
  Архив соответствующего исходного кода прилагается к APK; остальные продукты не затронуты.
- Исправлены stale command-line state, direct-mouse state при повторном init, передача
  firmware и ошибок init. Native frontend закрывает media handles и пересоздаёт звук.

## Verified results
- Native headless: BASIC Ready и PRINT 123; видео ANTIC/GTIA; XEX-игра/joystick/POKEY;
  save/restore/replay с сравнением RAM; ATR boot/SIO; CAR INIT/RUN; reload и bad-file.
- Те же C-тесты под AddressSanitizer (без leak checking process-lifetime core allocations).
- Тот же JNI-мост — host JVM integration passed.
- Java: Klaus success $3469 (30646177 instructions), 262144 binary + 40000 BCD cases.
- Android debug локально: BUILD SUCCESSFUL, arm64-v8a/x86_64.
- GitHub PR CI https://github.com/AlexSpec26/specialist-emu/actions/runs/40306954620 — success.
- Signed APK https://github.com/AlexSpec26/specialist-emu/actions/runs/40306947351 — success.
- APK: https://github.com/AlexSpec26/specialist-emu/releases/download/atari-build-5/atari-65xe-5.apk
- Source: https://github.com/AlexSpec26/specialist-emu/releases/download/atari-build-5/atari-source-5.tar.gz
- Скачанный release APK: apksigner verify passed; SHA-256 сертификата
  63fac01bd55da92d71b96dca374b44dcd4187ba65bd268b572bdb4b7b1d79c65 (ADR-014).
  zipalign -c -P 16 4 — passed. Manifest: com.alexspec.atari, version 0.3.5, SDK 24/35.
- Ключи агент не читал: подписывает только CI. На физическом телефоне не проверено.

## Known problems and risks
- Не все игры совместимы с Altirra; некоторые требуют original OS, другой RAM/NTSC.
- Реальные CAS/защищённые ATX отдельно не тестировались; пути доступны из upstream.
- RAM snapshot не откатывает запись в ATR. States привязаны к этой версии/профилю.
- Нет rewind/history и назначения кнопок на каждую программу; 32-битных ABI нет.
- Учебный инспектор — отдельная машина, не debugger игрового native runtime.
- Предыдущий handoff сохранён в docs/handoffs/2026-10-26-atari-cpu-milestone.md.
- ROM-DEV/SPEC-IDEA-002/ROM-Q1/Q2 не решались; другие продукты не менялись.

## Decisions made or proposed
- ADR-021-ATARI: готовое проверенное GPL-ядро для игрового APK вместо долгой реализации
  всех custom chips; собственный Java CPU сохранён для обучения.

## Exact next steps (ordered)
1. На телефоне установить build-5: ☰ → «Поймай звезду», затем проверить свою игру.
2. Review PR #3 владельцем; merge агентом не выполняется.
3. По конкретным отзывам исправлять совместимость/управление и расширять инструменты.

## Questions requiring the user's approval
- Блокирующих вопросов нет. Физическую проверку и решение о merge выполняет владелец.
