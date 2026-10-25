# Current handoff
- Updated at (UTC): 2026-10-25 14:10
- Outgoing agent: Claude (Claude Code, облачная сессия)
- Branch: main (короткие ветки на задачу пока не заводились — см. OPS-002)
- Last pushed commit: см. `git log -1` (коммит «Project memory...»); код ROM этапа 1 — 26fb442
- Open PR: нет
- Active backlog IDs: ROM-001 (следующий)
- User-approved goal for this session: «начнем с ядра и совместимости, а дальше посмотрим»
  (ROM этап 1 — сделан); затем «структурируй и запиши все» (память проекта — сделано)

## What changed and why
- ROM этап 1: ядро оболочки и совместимость (ADR-002…005), инструменты `shell/tools`.
- Память проекта по `AGENTS.md`; сборки разведены по продуктам (OPS-003), флиппер заморожен
  (ADR-012), база ПЗУ перенесена в `shell/base/spec_m2.rom`, CI для ПЗУ (`rom.yml`).

## Verified results
- Commands executed: сборка ПЗУ `asm80.py`; `ShellSmoke` (SHELL OK, pc=D166);
  `ShellCompat` на 127 играх (122 одинаково, см. ROM-000); замеры перерисовки (`Redraw`, в
  scratch — не в репозитории): «Обзор» ~0,94 с, заглушка ~0,38 с.
- Passed: базовые 2 КБ совпадают с Монитором-2 байт в байт.
- Not run / why: на телефоне ничего не проверялось (агент не имеет устройства). Новые
  workflow `rom.yml` и изменённые `build.yml`/`zx.yml`/`flipper.yml` проверятся первым запуском.

## Known problems and risks
- Медленный вывод текста (ROM-001).
- Treasure/Treasure 2: пейзаж другой (ROM-Q1). Заморозка будет портить экран игры (ROM-Q2).

## Decisions made or proposed
- Принятые: ADR-002…012. Предложено: ADR-013 (как изолировать продукты).

## Exact next steps (ordered)
1. ROM-001: быстрый вывод — в `shell/src/kernel.asm` добавить вывод строки группами по 4
   символа (3 байта экрана на группу), прокрутку окна сдвигом экранной памяти; замерить.
2. ROM-002 «Память» в `shell/src/apps.asm` (макет — VISION/BACKLOG).
3. ROM-003 «Отладка» (дизассемблер 8080 — можно взять таблицы из `shell/tools/dis80.py`).
4. Перед каждым коммитом ROM: собрать, `ShellSmoke`, по возможности `ShellCompat` (игры — см.
   ARCHITECTURE «Внешние тестовые ресурсы»).

## Questions requiring the user's approval
- ROM-Q1, ROM-Q2.
