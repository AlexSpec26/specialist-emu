# Current handoff
- Updated at (UTC): 2026-11-20
- Outgoing agent: Claude «Синклер» (Claude Code, облачная сессия; отвечает только за ZX — `zx/`)
- Branch: `claude/zx-standard`
- Last pushed commit: см. `git log -1 origin/claude/zx-standard`
- Open PR: #26 (ZX-016)
- Active backlog IDs: ZX-016 (Review)
- User-approved goal for this session: «В этой ветке мы сфокусируемся на эмуляторе ZX Spectrum … остальные
  ветки рассматриваем только для заимствования элементов интерфейса». Задача: «привести к общему стандарту —
  перемотка назад на 5 секунд РЕАЛЬНОГО времени, нормальный джойстик, убрать курсор с экрана, добавить телевизоры
  и пиксели»; уточнения: курсор = красное перекрестие, «ламповый» экран по умолчанию, история в сохранении.

## Другие продукты
- Предыдущая передача (DOS 286, SPEC-021, DOS-BUG-002) — `docs/handoffs/2026-11-17-claude-dos.md`. Расхождение,
  найденное при старте: в ней PR #18 значился открытым, а в main уже влиты #18–#25 (DOS-002…008, DOS-BUG-002,
  SPEC-021). Их вопросы к владельцу (DOS-BUG-001, ROM-Q1/Q2, старые предрелизы atari) — там, не закрыты.

## What changed and why
- ZX-016 / ADR-031: часы игрока в ядре (`Spectrum48.playerRate`, EmuThread задаёт из скорости), `backPlayer(5)` —
  ровно на 5 с назад повтором от снимка; кнопка ⟲ 5 с на панели; сохранения v2 с минутой истории (сжато, ~1 МБ).
- Экран через видеочип (порт SPEC-021): режимы, цветной ТВ по умолчанию, лупа на паузе; плавающий джойстик,
  1–8 кнопок; перекрестие луча убрано, скорость/кассета в панели.

## Verified results
- Commands executed: `javac -d /tmp/zt zx/src/main/java/com/alexspec/zx/core/*.java tools/ZxTimeTest.java &&
  java -cp /tmp/zt ZxTimeTest zx/src/main/assets/48k.rom "zx/src/main/assets/games/Sir Ababol.tzx"`;
  шейдер — moderngl/EGL (llvmpipe) на кадре Sir Ababol 320×256, 1440×1152, режимы 0/2/3/4/5 и лупа ×8.
- Passed: HISTORY 61,9 s, KEY REPLAY, STEP, PLAYER CLOCK ×1/×4/1⁄8, SAVE WITH HISTORY (1138 КБ), OLD AND BROKEN FILES.
- Not run: сборка APK локально (нет Android SDK, сеть к Google закрыта) — делает CI на PR #26; на телефоне ничего.

## Known problems and risks
- История — 62 с игры: при ×4 ⟲ 5 с можно нажать ~3 раза.
- Если видеочип не поднялся (шейдер не собрался) — картинка рисуется по-старому, без режимов и ч/б.
- Раскладка на телефоне не проверена: ⟲ на панели рядом с ☰, кнопки шагов на паузе сузились (между ⟲ и ⌨).

## Exact next steps (ordered)
1. Зелёный CI на PR #26 → влить → релиз zx-build-N.
2. Владелец: ⟲ 5 с при ×1, ×4 и замедлении; сохранить → выйти → загрузить → ⟲; экран (цветной ТВ, лупа); джойстик.
3. По отзывам — правки ZX-016.

## Questions requiring the user's approval
- Хватает ли 62 с игры (при ×4 — ~15 с реального времени) или увеличить историю?
