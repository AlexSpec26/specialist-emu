# Архитектура

```
app/        SPEC: Android-приложение «Специалист» (com.alexspec.specialist)
  core/     ядро без Android: I8080, Specialist (память, ВВ55, видео, звук, лента), MemTrace,
            TapeDecoder (микрофон → уровень), SpecKeys
  *.java    EmuActivity (меню), EmuThread (50 Гц, звук), MainView (экран, клавиатура, джойстик),
            MapPanel + Tutorial (карта памяти, экскурсия), MicTape, ControlConfig
zx/         ZX: Android-приложение ZX Spectrum 48K (com.alexspec.zx)
  core/     Z80, Spectrum48 (ULA, задержки, бордюр, история/шаги, часы игрока и ⟲ 5 с, сохранения
            v2 с историей — ADR-031), Tape (TAP/TZX), MemTrace, TapeDecoder, Z80Dis
  *.java    как в app + Games (игры в комплекте), Speed, ZxKeys, ScreenRenderer (экран через GLES 2,
            assets/screen.frag — тот же шейдер, что в DOS/SPEC; MainView над ним прозрачен в месте картинки)
  assets/   48k.rom, games/*.tap|tzx + LICENSE.txt
atari/      ATARI: Android-приложение Atari 65XE (com.alexspec.atari), подробно — atari/README.md
  java/     как в zx (EmuActivity, MainView, MapPanel, EmuThread…) + AtariKeys, Games, Tutorial;
            core/: AtariMachine, NativeAtari, MemTrace, Dis6502; ScreenRenderer (экран через GLES 2)
  jni/      native/engine.c (libretro-фронтенд), timeline.c (время, шаги, карта), jni.c (мост),
            vendor/atari800 (ядро)
dos/        DOS: Android-приложение «DOS 286» (com.alexspec.dos), подробно — dos/README.md
  java/     LibraryActivity (список игр), GameActivity (игра, процесс :game), GameView, EmuThread,
            PcKeys, GameFiles (копии игр, музей, C:\ИМЯ), LaunchChoice (корень и программа), ScreenRenderer (экран), NativeDos
  jni/      native/engine.c (libretro-фронтенд, история, сохранения), jni.c, vendor/dosbox-pure
shell/      ROM: оболочка в ПЗУ «Специалиста»
  src/      main.asm (оболочка) и devel.asm (среда разработки, ADR-019) — верхние файлы;
            common.asm, compat1–4.asm (Монитор-2), kernel.asm (текст, клавиатура),
            shell.asm (строки, переключение), apps.asm (Память, Магнитофон, Отладка),
            dbgcore/dbgui.asm, tapecore.asm, dis.asm, overview.asm,
            memdesc/scroll/hexin/gap_*.asm (лежат в пустотах ПЗУ, ADR-017),
            monitor.asm (отложен), devmon.asm (черновик), *.inc (генерируемые)
  base/     spec_m2.rom — источник базовых 2 КБ (C000–C7FF)
  tools/    asm80.py (ассемблер 8080), dis80.py, mkfont.py, mkkeys.py, lower.py (строчные)
  build/    shell.rom (собирается)
flipper/    FLIP (заморожен): spec_core.c, specialist_app.c, assets
tools/      настольные тесты (Java): CpuTest, MachineTest, ShellCompat, ShellSmoke, ShellTiming,
            ShellMemTest, ShellTapeTest, ShellDbgTest, ShellMonTest (отложен) (SPEC/ROM);
            Z80FuseTest, ZexTest, ZxShot (ZX); AtariNativeTest.c, AtariJniTest (ATARI);
            flipper_core_test.c (FLIP)
minimon/    учебный минимальный монитор «Специалиста» по шагам (ROM-MINI)
docs/       i8080-tutorial.html (DOC-001), handoffs/ (старые передачи)
.github/workflows/  build.yml (SPEC), zx.yml (ZX), atari.yml (ATARI), rom.yml (ROM),
                    flipper.yml (FLIP, вручную)
```

## Сборки и релизы
| Продукт | Запускается от | Тег релиза | Файл |
| --- | --- | --- | --- |
| SPEC | `app/**`, общие gradle-файлы, `tools/CpuTest.java`, `tools/MachineTest.java` | `build-N` | `specialist-N.apk` |
| ZX | `zx/**`, `tools/ZexTest.java`, `tools/Z80FuseTest.java`, `tools/ZxShot.java` | `zx-build-N` | `zx-spectrum-N.apk` |
| ATARI | `atari/**`, общие gradle-файлы, `tools/Atari*` | `atari-build-N` | `atari-65xe-N.apk` + `atari-source-N.tar.gz` |
| DOS | `dos/**`, общие gradle-файлы | `dos-build-N` | `dos-286-N.apk` + `dos-source-N.tar.gz` |
| ROM | `shell/**`, `tools/Shell*.java` | `rom-build-N` | `shell-N.rom` |
| FLIP | только вручную (workflow_dispatch) | `flipper-N` | `.fap` |

Подпись APK: только в CI из секретов `SIGNING_*` (ADR-014); без них сборка падает. Агенты ключ не
используют; локально release собирается неподписанным, debug — стандартным отладочным ключом.
Диагностика CI из облачной сессии: логи заданий закрыты прокси, но аннотации доступны:
`gh api repos/AlexSpec26/specialist-emu/check-runs/<job>/annotations`. Релиз — по тегу:
`gh api repos/AlexSpec26/specialist-emu/releases/tags/<tag>`.

## Как устроена память «Специалиста» (для ROM)
- 0000–8FFF ОЗУ (8F00–8FFF — переменные; база использует 8FDF–8FFF; Монитор-2 — 8F8A…8FAB),
  9000–BFFF экран (столбцы по 256 байт), C000–EFFF ПЗУ, F800–FFFF ВВ55 клавиатуры (4 регистра
  повторяются 512 раз).
- Клавиатура: матрица 12×6, код = столбец·6 + строка; столбцы — порт A и младшая тетрада C,
  строки — порт B биты 2–7, НР — B бит 1, лента — B бит 0.
- Оболочка: переменные 8F00–8F7F (с 8F30 — состояние программ), LBUF 8E00 (строка), INBUF 8E40
  (ввод), TVARS 8E50 (Магнитофон, имя файла), стек от 8F00 вниз до ~8E6E.
- Раскладка ПЗУ оболочки: C800–C835 точки входа; таблицы; совместимые подпрограммы (затем «Обзор»
  и описания адресов; CD21–CD28 байты Монитора-2 (их читает Piton 3), прокрутка; CE1D–CF3A
  фиксированно; ADR-017); ядро, оболочка, программы
  до EBFF; EC00–EFFF доп. символы 80–FF (выровнены по странице — так быстрее адрес глифа), в
  кодах BD–DF (EDE8–EEFF) — ввод hex.
- Вывод текста: GRP4 — 4 символа с колонки ×4 в 3 байта целиком; PUTCH — один символ (чтение-
  изменение-запись). Программам: строку в буфер, затем PUTS. Замер: `ShellTiming` (см. Команды).

## Внешние тестовые ресурсы (не в репозитории — лицензии)
| Что | Откуда | Зачем |
| --- | --- | --- |
| 127 игр .rks | github.com/vpyk/emu80v4 → `dist/spec/sdcard/GAMES` | ShellCompat, RomUse |
| ПЗУ ZX 48K | там же `dist/zx/48k.rom` (уже в `zx/src/main/assets`) | — |
| Тесты 8080 | github.com/superzazu/8080 `cpu_tests` | CpuTest (в CI SPEC) |
| zexdoc/zexall | github.com/superzazu/z80 `roms` | ZexTest (в CI ZX) |
| FUSE tests | github.com/floooh/chips-test `tests/fuse` | Z80FuseTest |
| Игры ZX | github.com/retrobrews/zxspectrum-games | источник игр в комплекте |

## Команды
- ROM: `cd shell/src && python3 ../tools/asm80.py main.asm -o ../build/shell.rom --base C000 --size 3000 -l ../build/shell.lst -s ../build/shell.sym`
- ROM, проверка совместимости:
  `javac -d /tmp/sp app/src/main/java/com/alexspec/specialist/core/*.java tools/ShellCompat.java` и
  `java -cp /tmp/sp ShellCompat <spec_m2.rom> shell/build/shell.rom <outdir> <GAMES>/*.rks`
- ROM, скорость: после `javac ... tools/ShellTiming.java` —
  `java -Dprofile=1 -cp /tmp/sp ShellTiming shell/build/shell.rom shell/build/shell.sym <outdir>`
  (такты по подпрограммам, PNG экранов).
- SPEC/ZX ядро: `javac` ядра + нужный тест из `tools/` (см. заголовки файлов).
- Android: `gradle :app:assembleRelease`, `gradle :zx:assembleRelease` (в CI, Gradle 8.11.1, JDK 17).

## Atari 65XE
- Ядро Atari800 7.0.0 / libretro, зафиксированная версия 4e7fbc7, исходники целиком в
  `atari/src/main/jni/vendor/atari800` (правки — `atari/THIRD_PARTY.md`). NDK 27.2 собирает
  `libatari_engine.so` для arm64-v8a и x86_64 (страницы 16 КБ), с `-DALEXSPEC_HOOKS`.
- `engine.c` — libretro-фронтенд: машина 800XL 64 КБ PAL, BASIC по настройке; кадр =
  `engine_frame_body` (джойстик через input-callback, клавиши — `INPUT_key_code` кодами Atari);
  картинка 336×240 из `Screen_atari`; снимок = save state ядра + состояние ввода из input.c.
- `timeline.c` — время (ADR-023): хук перед каждой командой считает команды и такты, отмечает
  обращения (карта) и по запросу выходит из ядра `longjmp`; продолжение — снимок начала кадра +
  повтор; кольцо снимков каждые 25 кадров (62 с), журнал органов управления по кадрам; часы игрока
  с журналом смен скорости и ⟲ N с; сохранение «ATS2» с минутой истории (ADR-032).
- Снимок = состояние ядра + то, чего ядро не сохраняет (клавиши между кадрами, загрузчик XEX и его
  запись ESC, обмен SIO, счётчик POT, кнопки GTIA, удержание OPTION/START при старте) — `engine.c`.
- Экран: `ScreenRenderer` (GLES 2) + `assets/screen.frag` — как в ZX; MainView прозрачен в месте картинки.
- Java: `core/AtariMachine` (интерфейс как у `Spectrum48`), экраны — порт из `zx/`
  (EmuActivity, MainView, MapPanel, EmuThread, ControlConfig, Speed), свои AtariKeys, Games,
  Tutorial, `core/Dis6502`. Все вызовы ядра — из потока эмуляции.
- Тесты на ПК: `tools/AtariNativeTest.c` (ядро и время; в CI под ASAN), `tools/AtariJniTest.java`
  (мост и AtariMachine).
- Лицензия модуля — GPL-2.0-or-later; в каждый релиз кладётся архив исходников
  (`atari/tools/package_source.py`), уведомления — `assets/licenses.txt` (`make_notices.py`).

## DOS 286
- Ядро DOSBox Pure 1.0-preview8 (libretro), зафиксировано e39497e, исходники целиком, одна правка (часы CMOS по времени эмулятора, ADR-029)
  в `dos/src/main/jni/vendor/dosbox-pure` (`dos/THIRD_PARTY.md`). NDK 27.2 собирает
  `libdos_engine.so` (C++ ядра + наш C) для arm64-v8a и x86_64 (страницы 16 КБ).
- `native/engine.c` — libretro-фронтенд: опции ядра таблицей (по умолчанию cycles 2750 = 286 на
  12,5 МГц, память 16 МБ, мышь «тачпад», меню запуска для zip), картинка XRGB8888 → ARGB до
  1280×1024, звук стерео 48 кГц, клавиатура кодами RETROK, мышь через POINTER.
- Перемотка (ADR-025, ADR-027): часы истории — время игрока (кадр = замедление/fps, пауза стоит);
  снимок ядра каждые 5 с этих часов, кольцо 13 (минута назад; не больше 160 МБ, но не меньше 4);
  «назад на N с» = самый новый снимок не позже N с назад, более новые отбрасываются. Без журнала
  ввода и без повтора, DOSBox не обязан повторяться покадрово. В приложении одна кнопка ⟲ 5 с.
- Сохранения (ADR-027, ADR-029): контейнер `DOSHIST2` — текущее состояние ядра, начало часов CMOS и
  все снимки истории с их временем; `DOSHIST1` и голое состояние ядра (раньше) тоже грузятся.
- Часы (ADR-029): таймер (PIT, тики BIOS, время DOS) в DOSBox и так идёт по времени эмулятора; часы
  CMOS (порты 70h/71h) ядро брало у телефона — правка в cmos.cpp: начало от телефона при старте машины,
  дальше по времени эмулятора, так что замедление и перемотка честны и для них.
- Игры (ADR-026): files/museum/<игра> — нетронутая копия (только чтение), files/games/<игра> —
  рабочая; files/mounts/<игра>/<ИМЯ> — ссылка на корень игры, монтируется как C:, игра видна как C:\ИМЯ.
- Запуск (ADR-028, `LaunchChoice`): корень и программа выбираются при добавлении (настройки root:/exe:);
  GameActivity пишет files/mounts/<игра>/AUTOBOOT.DBP = C:\ИМЯ\ПРОГРАММА, и DOSBox стартует её без меню.
- Экран (ADR-030): `ScreenRenderer` (GLSurfaceView под GameView, GLES 2) + `assets/screen.frag` — режимы
  «чёткие пиксели» (одной ширины), VGA, цветной/ч-б телевизор, зелёный/янтарный монитор; лупа на паузе
  (zoom/центр в шейдер). GameView оставляет место картинки прозрачным. Проверка шейдера на ПК — moderngl/EGL.
- Джойстик (`GameView`): плавающий — центр там, где палец коснулся зоны слева; кнопки справа — сетка
  наибольшего размера, не шире ¼ экрана.
- Ядро запускается один раз на процесс (повторный старт падает внутри DOSBox), поэтому игра —
  отдельный процесс `:game` (`GameActivity`), список игр — `LibraryActivity` в основном процессе;
  выход из игры завершает её процесс, «начать заново» перезапускает его через список.
- Java: `EmuThread` (кадр за кадром, темп задаёт блокирующая запись звука; замедление ×1,5…×8 = кадр на
  N кадров времени со звуком, растянутым в N раз, дробный остаток переносится на следующий кадр), `GameView` (картинка, кнопки, джойстик,
  клавиатура ПК 6×15), `PcKeys`, `NativeDos` (JNI). Сохранения — `files/states/<игра>.quick|.auto`,
  собственные сохранения игр ядро пишет в `files/saves`.
- Тест на ПК: `dos/tools/DosNativeTest.c` (загрузка, клавиша, минута истории, ⟲ 5 с с повтором
  кадров, часы игрока при ×8 и ×1,5, сохранение с историей и старое без неё, меню без игры, AUTOBOOT,
  часы CMOS по времени эмулятора — RTC.COM) — `make -f dos/src/main/jni/Makefile.host test`, в CI перед сборкой.
