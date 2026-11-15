# Current handoff
- Updated at (UTC): 2026-11-15
- Outgoing agent: Claude (Claude Code, облачная сессия)
- Branch: `claude/dos-rewind-5s` (от main aedb6a3, PR — см. ниже)
- Last pushed commit: см. `git log -1 origin/claude/dos-rewind-5s`
- Open PR: DOS-002 (ветка выше)
- Active backlog IDs: DOS-002 (Review), DOS-001 (Review — отзывы с телефона)
- User-approved goal for this session: в этой ветке — только эмулятор DOS 286; остальные продукты —
  лишь как источник элементов интерфейса. Задача: «посекундные снимки убрать, оставить 5-секундные,
  минута; save — всё состояние на минуту назад; скорость в 1,5 раза медленнее; перемотка на 5 с по
  субъективному времени».

## What changed and why
- DOS-002 / ADR-027: история DOS по времени игрока (кадр = замедление/fps), снимок раз в 5 с, 13
  снимков = минута назад; кнопка ⟲ 1 с убрана; сохранения (быстрое, «как…», автосохранение) —
  контейнер DOSHIST1 с историей, старые читаются; скорость ⅔ (1,5×) с дробным растяжением звука.
- Документы догнаны: в прошлой передаче не было PR #12–#16 (dos-build-4…13), они описаны в BACKLOG
  DOS-001 и теперь в CHANGELOG; в ARCHITECTURE добавлены `dos/` в дерево и музей (ADR-026).
- Предыдущая передача (Atari, DOS-001 начало) — docs/handoffs/2026-11-11-claude-atari-dos.md.

## Verified results
- Commands executed: `make -f dos/src/main/jni/Makefile.host test`; то же с
  `OUT=/tmp/dos-asan EXTRA_CFLAGS="-fsanitize=address -fno-omit-frame-pointer"`.
- Passed: BOOT, KEY, REWIND 5 s (история 62,6 с, 13 снимков, 40/40 кадров после отката),
  SLOW MOTION CLOCK (×8 и ×1,5), STATE с 13 снимками (6,2 МБ; после загрузки ⟲ 60 с; голое старое
  состояние; испорченный файл отвергнут), START MENU, MENU KEYS ×2, NO MIDI CARD; ASAN чисто.
- Not run: сборка APK локально (нет Android SDK) — её делает CI на PR; на телефоне ничего.

## Known problems and risks
- ⟲ 5 с уводит на 5–10 с (снимки раз в 5 с) — так понята просьба, ждём подтверждения владельца.
- Файлы сохранений крупнее (~5–10 МБ).
- Часы игрока — кадрами; если телефон не тянет выбранный процессор на 1×, «5 с» дольше настенных.

## Decisions made or proposed
- ADR-027 (принят по просьбе владельца; трактовка «не меньше 5 с» — агента, см. DOS-002).

## Exact next steps (ordered)
1. Дождаться зелёного CI PR, влить, релиз dos-build-N собирается с main.
2. Владелец: проверить ⟲ 5 с подряд до минуты, при ⅔ и ⅛, сохранить → выйти → загрузить → отмотать.
3. По отзывам — правки DOS-002; DOS-001: «Открыть с помощью», dosbox_fs.conf (отложены).

## Questions requiring the user's approval
- Устраивает ли 5–10 с вместо ровно 5 с (или брать ближайший снимок: 2,5–7,5 с)?
- ROM-Q1, ROM-Q2; старые предрелизы atari-build-1…7 — удалить или оставить (с прошлых сессий).
