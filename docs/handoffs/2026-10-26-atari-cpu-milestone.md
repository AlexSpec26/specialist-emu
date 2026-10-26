# Current handoff
- Updated at (UTC): 2026-10-26
- Outgoing agent: ChatGPT-Codex
- Branch: feature/atari-65xe (продолжена от 88e6750)
- Last pushed implementation commit: befcff0 (документация — см. git log -1)
- Open PR: https://github.com/AlexSpec26/specialist-emu/pull/3
- Active backlog IDs: ATARI-001 — Review
- User-approved goal: «посмотри в репозитории, можешь продолжить работу в ветке про атари?»

## What changed and why
- Выполнен первый этап roadmap: 151 документированный opcode NMOS 6502, двоичная/BCD
  арифметика, стек, IRQ/NMI/BRK, page-cross/branch timing и NMOS JMP wrap.
- Исправлены неверная полярность OS PORTB в исходном каркасе, ROM write protection,
  BASIC/self-test overlay; PIA DDR/control и зеркала банков вместо прямой записи PORTB.
- Загрузка своих OS (16K)/BASIC (8K), регистры, код у PC и память, шаги 1/100, переход к PC.
  Работа вне UI; ROM сохраняются между запусками. Игры и Atari video ещё не реализованы.
- Изолированный workflow Atari и подписанный prerelease atari-build-1.
- app/, zx/, shell/, minimon/ не менялись. SPEC PR #2 владелец подтвердил рабочим; не merged.

## Verified results
- AtariCoreTest: 262144 binary ADC/SBC + 40000 valid BCD cases, NMOS decimal flags,
  page-cross/branch/RMW/stack/interrupts, banking и CPU bus — passed.
- Klaus 6502 functional test: success $3469, 30646177 instructions, 96241367 cycles.
- `gradle --offline --no-daemon :atari:assembleDebug` — BUILD SUCCESSFUL, 16 s.
- PR CI https://github.com/AlexSpec26/specialist-emu/actions/runs/40290700296 — success.
- Signed APK https://github.com/AlexSpec26/specialist-emu/actions/runs/40290695602 — success.
- APK: https://github.com/AlexSpec26/specialist-emu/releases/download/atari-build-1/atari-65xe-1.apk
- Опубликованный APK скачан и проверен apksigner: SHA-256 сертификата
  63fac01bd55da92d71b96dca374b44dcd4187ba65bd268b572bdb4b7b1d79c65 (ADR-014).
  Manifest: com.alexspec.atari, version 0.2.1, minSdk 24, targetSdk 35, launcher AtariActivity.
- Телефон: не проверено. Ключи не читались, подписывал только GitHub Actions.

## Known problems and risks
- Нет ANTIC/GTIA/POKEY, DMA/RDY, звука, клавиатуры/джойстика и игровых форматов.
- Нет continuous execution и save states. Шаги позволяют изучать CPU, не запуск игр.
- Недокументированные opcodes останавливают с явной ошибкой; timing не cycle-by-cycle.
- Сброс — CPU/PIA preserving RAM, ещё не аппаратная кнопка RESET Atari.
- ROM не входят в APK; пользователь выбирает собственные файлы нужного размера.
- Предыдущая память проекта сохранена: docs/handoffs/2026-10-26-before-atari.md.
  ROM-DEV на паузе; ROM-Q1/ROM-Q2 и SPEC-IDEA-002 не решались в этой работе.

## Decisions made or proposed
- ADR-020-ATARI: сначала подтверждённый CPU и память, затем custom chips.

## Exact next steps (ordered)
1. Проверить текущий inspector APK на устройстве, review PR #3 (без merge агентом).
2. Следующий этап реализации: ANTIC display lists/DMA + GTIA, синтетические тестовые экраны,
   затем проверка реального OS boot без распространения ROM в репозитории.
3. POKEY/ввод, XEX и ATR/CAR; только затем заявлять запуск игр.

## Questions requiring the user's approval
- Нет блокирующих вопросов для следующих этапов текущего roadmap.
