# Current handoff
- Updated at (UTC): 2026-11-22
- Outgoing agent: Claude «Майнер» (Claude Code, облачная сессия; отвечает только за Atari — `atari/`, `tools/Atari*`)
- Branch: `claude/atari-standard`
- Last pushed commit: см. `git log -1 origin/claude/atari-standard`
- Open PR: ATARI-BUG-002 (ветка `claude/atari-rewind-live`); PR #28 (ATARI-008) влит, релиз atari-build-26
- Active backlog IDs: ATARI-BUG-002, ATARI-008 (Review)
- User-approved goal for this session: «В этой ветке мы сфокусируемся на эмуляторе Atari, ты будешь отвечать за
  него … остальные ветки рассматриваем только для заимствования элементов интерфейса». Задача: «Atari надо
  привести к "общему стандарту" - чтобы перемотка назад на 5 секунд РЕАЛЬНОГО времени, нормальный джойстик,
  убрать курсор с экрана, добавить телевизоры и пиксели и т.д.»; на план — «давай». Уточнения взяты из ответов
  владельца для ZX (курсор = красное перекрестие, ламповый экран по умолчанию, история в сохранении).

## Другие продукты
- Предыдущая передача (ZX «Синклер», ZX-016) — `docs/handoffs/2026-11-20-claude-zx.md`; её вопрос (хватает ли 62 с
  игры для ⟲ при ×4) относится и к Atari. Более ранние — там же.

## What changed and why
- 2026-11-22, SPEC-022 (ветка `claude/spec-bot-app`): бот-исследователь — ядро `core/Explorer`, вызывается
  из EmuThread перед каждым кадром (планирование блокирует поток эмуляции: не успевает телефон — игра
  идёт медленнее), меню «🤖 Бот…», линии поиска в MainView, профили `assets/bot`. ПК-инструменты в
  `tools/bot` (README там же). Проверено на ПК (`ExplorerRun`), в приложении — только сборка CI.
- ATARI-008 / ADR-032: часы игрока в `timeline.c` с журналом смен скорости (EmuThread задаёт секунды игрока на
  такт); `engine_back_player(5)` — ровно на 5 с назад даже при смене скорости; кнопка ⟲ 5 с; сохранение «ATS2»
  с минутой истории (zlib, ~2 МБ), «ATS1» читается.
- Снимки дополнены тем, что ядро не сохраняет (загрузчик XEX + ESC, SIO, POT, GTIA_TRIG, удержание OPTION):
  без этого перемотка в начало программы и загрузка сохранения сразу после открытия расходились (нашёл новый тест).
- Экран через видеочип (ScreenRenderer + screen.frag из ZX), лупа; плавающий джойстик, 1–8 кнопок; без
  перекрестия; скорость и заметка ⟲ в панели.

## Verified results
- Commands executed: `python3 atari/tools/make_demo.py && python3 atari/tools/make_notices.py`;
  `ASAN_OPTIONS=detect_leaks=0 make -s -f atari/src/main/jni/Makefile.host test EXTRA_CFLAGS="-O0 -fsanitize=address -g -fno-omit-frame-pointer" DEMO=$PWD/atari/src/main/assets/demos/catch-the-star.xex`;
  `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 make -s -f atari/src/main/jni/Makefile.host jni-test DEMO=...`;
  шейдер — moderngl/EGL (llvmpipe) на кадрах 336×240, режимы 0/2/3/4/5 и лупа ×8.
- Passed: все проверки AtariNativeTest (в т. ч. новый PLAYER CLOCK…), AtariJniTest; CI PR #28 (сборка APK).
- Not run: на телефоне ничего; Android-часть компилирует только CI (локально нет SDK, Maven закрыт).

## Known problems and risks
- История — 62 с игры: при ×4 ⟲ 5 с можно нажать ~3 раза (как в ZX).
- Буфер SIO в снимке — до 1027 байт (большие кадры FujiNet — нет). Диск ATR снимки не откатывают (как раньше).
- Раскладка на телефоне не проверена: ⟲ на панели рядом с ☰, в горизонтали — под ☰; кнопки шагов сузились.
- Если видеочип не поднялся (шейдер не собрался) — картинка рисуется по-старому, без режимов и лупы.

## Exact next steps (ordered)
1. Сделано: PR #28 влит, atari-build-26. Слить PR ATARI-BUG-002 (с согласия владельца) → следующий atari-build.
2. Владелец: ⟲ 5 с при ×1, ×4 и замедлении; сохранить → выйти → загрузить → ⟲; экран (цветной ТВ, лупа);
   джойстик и кнопки 5–8; River Raid и любимые игры.
3. По отзывам — правки ATARI-008.

## Questions requiring the user's approval
- Устраивают ли для Atari те же решения, что для ZX (перекрестие убрано, цветной ТВ по умолчанию, минута истории)?
- Кнопки 5–8 по умолчанию — SELECT, OPTION, 1, 2: подходит?
