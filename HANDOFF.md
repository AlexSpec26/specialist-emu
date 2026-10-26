# Current handoff
- Updated at (UTC): 2026-10-26
- Outgoing agent: Claude (Claude Code, облачная сессия)
- Branch: main (ветка feature/atari-65xe слита через PR #3)
- Last pushed commit: см. `git log -1`
- Open PR: #2 SPEC-019 (Codex, управление временем в «Специалисте») — не слит, при слиянии
  будут конфликты в HANDOFF/BACKLOG/DECISIONS (у него свой ADR-020)
- Active backlog IDs: ATARI-002 (ждёт проверки на телефоне), ROM-MINI (учебный монитор, шаг 2 готов)
- User-approved goal for this session: «навести порядок и нормально сделать и задокументировать
  приложение под атари»; инспектор — убрать; после зелёного CI — слить в main

## What changed and why
- ATARI-003: убран учебный Java-инспектор (ADR-022) — в APK была вторая, не связанная с игрой
  машина. Код Java/C переписан читаемо без изменения поведения, меню разделено на основное и
  «Настройки», atari/README.md переписан. CI Atari: релизы только с main, без теста Klaus.
- Память проекта сведена: VISION, ARCHITECTURE (+ исправлены устаревшие имена файлов shell/),
  DECISIONS, BACKLOG (ATARI-003…006), CHANGELOG, корневой README. Передача Codex по Atari —
  docs/handoffs/2026-10-26-codex-atari-build5.md.

## Verified results
- AtariNativeTest (настоящее ядро: BASIC, XEX+джойстик+POKEY, снимки, ATR, CAR, ошибки) — OK,
  также под AddressSanitizer; AtariJniTest на JVM — OK; engine.c/jni.c без предупреждений -Wall -Wextra.
- Java-часть Atari: только компиляция в CI (локально нет Android SDK) — см. статус CI PR #3.
- Перед уборкой проверены заявления Codex: те же тесты и Klaus 6502 — проходили.
- ROM: оболочка собирается (12052 из 12288 байт), ShellSmoke — SHELL OK; minimon step1/2 собираются.
- Not run: на телефоне ничего не проверялось.

## Known problems and risks
- Слияние изменило settings.gradle (+ :atari) → на main пересоберутся SPEC и ZX (новые build-N,
  zx-build-N без изменений в коде) — заодно проверка, что модуль :atari им не мешает.
- Atari: нет карты памяти/шагов/скорости/кнопок на игру (ATARI-004…006); не все игры идут
  с Altirra OS; ATR-записи не откатываются снимком.
- ROM: ROM-Q1 (пейзаж Treasure), ROM-Q2 (заморозка портит экран); ROM-DEV на паузе.

## Decisions made or proposed
- ADR-022 (принят): одна машина в Atari, инспектор убран; заменяет ADR-020-ATARI.

## Exact next steps (ordered)
1. Владелец: поставить atari-build-N с main на телефон, проверить BASIC, «Поймай звезду», свою игру.
2. По выбору владельца: ROM-MINI шаг 3; ATARI-004…006; ревью/слияние PR #2 (SPEC-019).
3. Перед коммитами ROM: сборка, ShellSmoke, ShellTiming, по возможности ShellCompat.

## Questions requiring the user's approval
- ROM-Q1, ROM-Q2; что делать с PR #2; приоритет ATARI-004…006.
