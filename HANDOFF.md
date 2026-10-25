# Current handoff
- Updated at (UTC): 2026-10-25 15:55
- Outgoing agent: Claude (Claude Code, облачная сессия)
- Branch: main (короткие ветки на задачу пока не заводились — см. OPS-002)
- Last pushed commit: см. `git log -1` (коммит «ROM-001 ...»)
- Open PR: нет
- Active backlog IDs: ROM-002 (следующий)
- User-approved goal for this session: ядро и совместимость (сделано), память проекта (сделано),
  ключ подписи в секретах (OPS-001, сделано), «давай» → ROM-001 (сделано)

## What changed and why
- OPS-001: подпись APK только из секретов; старый ключ удалён; build-23 и zx-build-6 подписаны
  новым ключом (отпечаток сверен в APK).
- ROM-001: GRP4 (4 символа → 3 байта), PUTS/REPCH через него, SCROLLUP/SCROLLDN; глифы 80–FF
  перенесены в EC00; свободное место до CE1D — байты Монитора-2 (ADR-015).

## Verified results
- `ShellTiming`: «Обзор» 955 → 595 мс, строка 64 символа 44 → 24 мс, прокрутка 30 строк 109 мс;
  экраны байт в байт как до изменений; прокрутка вверх/вниз совпадает с эталоном.
- `ShellSmoke` — SHELL OK; `ShellCompat` — 122 из 127 одинаково (как и раньше).
- Not run / why: на телефоне не проверялось.

## Known problems and risks
- Цели ROM-001 (0,35 с / 0,1 с) не достигнуты — см. BACKLOG ROM-001; программам печатать
  строками через буфер и PUTS.
- Treasure/Treasure 2: пейзаж другой (ROM-Q1). Заморозка будет портить экран игры (ROM-Q2).

## Decisions made or proposed
- Принятые: ADR-002…015.

## Exact next steps (ordered)
1. ROM-002 «Память» в `shell/src/apps.asm` (макет — VISION/BACKLOG): строки собирать в буфере
   APPVARS и печатать PUTS с колонки ×4; прокрутка — SCROLLUP/SCROLLDN.
2. ROM-003 «Отладка» (дизассемблер 8080 — таблицы можно взять из `shell/tools/dis80.py`).
3. Перед каждым коммитом ROM: собрать, `ShellSmoke`, `ShellTiming`, по возможности
   `ShellCompat` (игры — см. ARCHITECTURE «Внешние тестовые ресурсы»).

## Questions requiring the user's approval
- ROM-Q1, ROM-Q2.
