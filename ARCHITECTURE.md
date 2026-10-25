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
shell/      ROM: оболочка в ПЗУ «Специалиста»
  src/      main.asm (раскладка ПЗУ), compat.asm (Монитор-2), kernel.asm (текст, клавиатура),
            shell.asm (строки, переключение), apps.asm (программы), *.inc (генерируемые)
  base/     spec_m2.rom — источник базовых 2 КБ (C000–C7FF)
  tools/    asm80.py (ассемблер 8080), dis80.py, mkfont.py, mkkeys.py, lower.py (строчные)
  build/    shell.rom (собирается)
flipper/    FLIP (заморожен): spec_core.c, specialist_app.c, assets
tools/      настольные тесты (Java): CpuTest, MachineTest, ShellCompat (SPEC/ROM);
            Z80FuseTest, ZexTest, ZxShot (ZX); flipper_core_test.c (FLIP)
.github/workflows/  build.yml (SPEC), zx.yml (ZX), rom.yml (ROM), flipper.yml (FLIP, вручную)
```

## Сборки и релизы
| Продукт | Запускается от | Тег релиза | Файл |
| --- | --- | --- | --- |
| SPEC | `app/**`, общие gradle-файлы, `tools/CpuTest.java`, `tools/MachineTest.java` | `build-N` | `specialist-N.apk` |
| ZX | `zx/**`, `tools/ZexTest.java`, `tools/Z80FuseTest.java`, `tools/ZxShot.java` | `zx-build-N` | `zx-spectrum-N.apk` |
| ROM | `shell/**`, `tools/ShellCompat.java` | `rom-build-N` | `shell-N.rom` |
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
- Оболочка: переменные 8F00–8F7F, стек под 8F00.

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
- SPEC/ZX ядро: `javac` ядра + нужный тест из `tools/` (см. заголовки файлов).
- Android: `gradle :app:assembleRelease`, `gradle :zx:assembleRelease` (в CI, Gradle 8.11.1, JDK 17).
