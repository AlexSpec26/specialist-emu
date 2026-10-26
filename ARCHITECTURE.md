# Архитектура

```
app/        SPEC: Android-приложение «Специалист» (com.alexspec.specialist)
  core/     ядро без Android: I8080, Specialist (память, ВВ55, видео, звук, лента), MemTrace,
            TapeDecoder (микрофон → уровень), SpecKeys
  *.java    EmuActivity (меню), EmuThread (50 Гц, звук), MainView (экран, клавиатура, джойстик),
            MapPanel + Tutorial (карта памяти, экскурсия), MicTape, ControlConfig
zx/         ZX: Android-приложение ZX Spectrum 48K (com.alexspec.zx)
  core/     Z80, Spectrum48 (ULA, задержки, бордюр, история/шаги), Tape (TAP/TZX), MemTrace,
            TapeDecoder, Z80Dis
  *.java    как в app + Games (игры в комплекте), Speed, ZxKeys
  assets/   48k.rom, games/*.tap|tzx + LICENSE.txt
atari/      ATARI: Android-приложение Atari 65XE (com.alexspec.atari), подробно — atari/README.md
  java/     AtariActivity (меню, импорт), PlayView (экран, джойстик, клавиатура),
            AtariRunner (поток эмуляции, звук, снимки), NativeAtari (вызовы ядра)
  jni/      native/engine.c (libretro-фронтенд), native/jni.c (мост), vendor/atari800 (ядро)
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
  `atari/src/main/jni/vendor/atari800` (три локальные правки — `atari/THIRD_PARTY.md`).
  NDK 27.2 собирает `libatari_engine.so` для arm64-v8a и x86_64 (страницы 16 КБ).
- `engine.c` — libretro-фронтенд: машина зафиксирована (800XL 64 КБ, PAL, 336×240,
  BASIC по настройке), RGB565 → ARGB, звук 44,1 кГц стерео, джойстик и клавиши кодами libretro.
  Машина в ядре одна на процесс, поэтому все вызовы — из `AtariRunner` под монитором
  `NativeAtari.class`; новый поток (после пересоздания экрана) забирает ядро у старого.
- Снимки: `states/<профиль>-quick|auto.state`, профиль = SHA-256 от версии ядра, файла
  программы, ПЗУ и настройки BASIC; запись через AtomicFile, проверка хеша при чтении.
- Тесты на ПК: `tools/AtariNativeTest.c` (настоящее ядро; в CI под ASAN), `tools/AtariJniTest.java`.
- Лицензия модуля — GPL-2.0-or-later; в каждый релиз кладётся архив исходников
  (`atari/tools/package_source.py`), уведомления — `assets/licenses.txt` (`make_notices.py`).
