# Current handoff
- Updated at (UTC): 2026-10-25 23:49
- Outgoing agent: ChatGPT-Codex
- Branch: codex/spec-019-time-controls
- Last pushed implementation commit: 03e47a2 (после него синхронизация main и документация; `git log -1`)
- Open PR: https://github.com/AlexSpec26/specialist-emu/pull/2 (без merge)
- Active backlog IDs: SPEC-019 — Review
- User-approved goal for this session: управление временем из ZX в app; shell/ и zx/ без изменений.

## What changed and why
- ±1 инструкция 8080, ±1 секунда, история около 10 с через снимки + replay с точным вводом клавиш.
- Восстановление CPU (pending EI), памяти/цвета, ВВ55, времени, состояния аудио. Save v2 читает v1.
- Ползунок ×4…1 такт/с, индикатор скорости, кнопки паузы, занятость и граница истории.
- Работа в EmuThread; повторные запросы блокируются; внешняя лента исключает недетерминированный replay.
- PR-проверки без ключей выпуска и публикации релизов; ZX собирается как явная проверка SPEC-019.
- Удалена тестовая ветка codex/github-write-test по просьбе владельца.
- Сохранены актуальные изменения Claude (origin/main a0c142f), включая DOC-001 и SPEC-IDEA-002.
  Предыдущая передача: docs/handoffs/2026-10-25-claude-before-spec019.md.

## Verified results
- `gradle :app:testDebugUnitTest :app:assembleDebug :zx:assembleDebug` — BUILD SUCCESSFUL (28 s).
- 9 JUnit tests: 0 failures/errors/skipped; replay через снимки, CPU/RAM/ВВ55/цвет, клавиши,
  EI/HLT, секунды/границы 10 с, v1 saves, повреждённый save, внешняя лента, звук побитно.
- Desktop CpuTest: TST8080 — CPU IS OPERATIONAL; 8080PRE — complete; CPUTEST — CPU TESTS OK.
- `git diff origin/main -- shell zx` — пусто; исходники других эмуляторов не менялись.
- Not run: на телефоне не проверялось; подписанный release не собирался (ключи только в CI).
- Initial environment failures resolved: установлен JDK/Gradle/SDK в /workspace/toolchains,
  сетевые зависимости через proxy; ANDROID_USER_HOME задан в writable /workspace.

## Known problems and risks
- История занимает ~30 МиБ RAM, сбрасывается при загрузках/reset/внешней записи памяти.
- Вход с микрофона/внешняя запись очищают историю; UI просит отключить их перед шагами.
- Временные шаги округляются до инструкции; HLT = 4 такта ожидания; скорость 1 такт/с — средняя
  (ядро выполняет целые инструкции). Новые v2 saves не читаются старой версией приложения.
- Требуется проверка размеров/удобства кнопок на телефоне.
- Сохранённые блокеры ROM (вне задачи): ROM-DEV на паузе; ROM-Q1, ROM-Q2; цели ROM-001 по
  скорости не достигнуты. См. предыдущую передачу и BACKLOG.

## Decisions made or proposed
- ADR-020: адаптация истории ZX к 8080 с точным журналом клавиш и явным ограничением live tape.

## Exact next steps (ordered)
1. Проверить PR #2 и CI; на устройстве проверить паузу, ±1 команду, ±1 с и ползунок скорости.
2. Владельцу решить о merge PR; агент не выполняет merge в main.
3. Другие направления (SPEC-IDEA-002, ROM-DEV) только по отдельной просьбе владельца.

## Questions requiring the user's approval
- Для завершённой реализации SPEC-019 нет; review/merge выполняет владелец.
- Не снятые вопросы ROM: ROM-Q1, ROM-Q2 (не относятся к этой работе).
