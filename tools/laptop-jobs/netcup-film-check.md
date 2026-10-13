# Задание netcup: проверка планов трейлера на процессоре

Ветка `claude/project-thread-45f5nj` (zentixua/incoming), коммит — в сообщении координатора. Цель — проверка постановки
по данным, как в облаке, но быстрее: запись планов повторами и вывод с проверкой каждого кадра (`film.sh record|render`)
без видеокарты, шейдеров и Distant Horizons, кадр 640×360. Кадры в монтаж не идут: чистовой вывод — на ноутбуке. Итог
нужен, чтобы ошибки постановки (объект вне кадра, закрыт, не успел) находились до вывода на ноутбуке.

Не трогать: сервер Артёма, его миры (в том числе золотую копию Zearth — только копировать из неё), чужие службы на
машине; свободного места — не меньше 4 ГБ. Одна съёмка на рабочее дерево (Gradle и `mod/run` общие); вторая параллельно
— в своём `git worktree`. Копии Zearth — только в `mod/run/scenario/saves/` и удаляются после съёмки (лицензия карты:
без распространения, наружу — только текстовые итоги).

## Подготовка (один раз)
- Моды съёмки — каталог `mod/run/film-fb-mods`: те же версии, что в копии для съёмки на ноутбуке (сборка open-skies):
  Flashback 0.39.10, Flashback NeoForge Fixed 1.0.15, Sinytra Connector 2.0.0-beta.17+1.21.1 (full), Forgified Fabric
  API 0.116.15+2.3.5+1.21.1, Create 6.0.10, Create Aeronautics 1.3.2 (bundled), Sable 2.0.6, GeckoLib 4.9.3, Immersive
  Aircraft 1.6.0, Point Blank 2.2.0, Almighty 0.4.0. Откуда и хеши — `pack/mods/*.pw.toml` в zentixua/open-skies
  (`tools/pack_dir.py` там собирает каталог игры из сборки), Almighty — выпуск v0.4.0 zentixua/almighty. Без Sodium,
  Iris и DH: процессорная отрисовка.
- «Чистый» jar клиента для Connector: установщик NeoForge 21.1.259 (`--installClient <каталог>`), файл
  `<каталог>/libraries/net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-srg.jar` —
  `INCOMING_CONNECTOR_CLEAN`.
- Мир — копия золотой Zearth из `~/trailer-war` (папка мира с `level.dat` и `region/`).

## Съёмка части
```sh
export JAVA_HOME=~/jdk-21.0.12.1+1 INCOMING_DH= INCOMING_CLOUDS=false INCOMING_FILM_CLOUDS=320 INCOMING_FILM_READY=0 \
       INCOMING_CONNECTOR_CLEAN=<srg jar> INCOMING_MODS_DIR=run/film-fb-mods INCOMING_SIZE=640x360 \
       INCOMING_LANG=en_us INCOMING_RENDER_DISTANCE=24
D=check-<часть>
cp -r <копия Zearth> mod/run/scenario/saves/$D
INCOMING_WAR_PART=<часть> INCOMING_WORLD=$D INCOMING_TRAILER_DIR=$D tools/trailer/film.sh record
INCOMING_WAR_PART=<часть> INCOMING_WORLD=$D INCOMING_TRAILER_DIR=$D tools/trailer/film.sh render
python3 tools/trailer/report.py mod/run/scenario/$D/render --md mod/run/scenario/$D/render/report.md
rm -rf mod/run/scenario/saves/$D
```
`INCOMING_FILM_READY=0`: процессор строит мир медленно, доля «мир» у вывода здесь не показательна (её проверяет ноутбук).
Клиент идёт под `xvfb-run` с программной отрисовкой (`record.sh` без KWin сам так делает).

## 1. Проба: план 21
Часть `p21`. Ждём в `mod/run/scenario/logs/latest.log`: `SCENARIO film verdict p21 own-35mm: PASS` (камера сценария:
рой входит в кадр сверху, удар — у середины кадра). Прислать строки `SCENARIO film` и `render/report.md`.

## 2. Вся война
Части `all`, `nukeB`, `nukeC`, `icbm` — каждая на свежей копии Zearth (папки `check-all` и т. д.), две части сразу — во
втором рабочем дереве. Прислать по каждой: `render/report.md`, `render/best.txt`, `hud.jsonl` (живые планы с проверкой
кадров, например p33), строки `SCENARIO film verdict` и `SCENARIO film hud`, ошибки из лога (`Exception`, `ERROR` мода).

## 3. Карта для постановки (только чтение файлов регионов)
Регион — `<мастер Zearth>/region` (`~/airstrike-work/zearth-master/region`; эталон не трогать). Скрипты — `uv run`
(numpy). Прислать вывод каждой команды как есть (текст; файлы карты наружу не уходят).
```sh
R=~/airstrike-work/zearth-master/region
L=light,lantern,glowstone,froglight,shroomlight,end_rod,lamp,bulb,torch,candle,glow
# p16: из чего свет станции метро (гаснут при блэкауте мода только лампы, не факелы)
uv run tools/trailer/blocks.py $R 3330 20 240 3470 64 300 --match $L --list 5 --grid 8
# p38: свет кварталов вокруг площади ночью
uv run tools/trailer/blocks.py $R 3000 55 150 3400 260 450 --match $L --list 3 --grid 16
# p04: пути депо (ванильные рельсы или пути Create)
uv run tools/trailer/blocks.py $R 2700 40 -300 3700 110 900 --match rail,create:track --list 30 --grid 16
# p07: наводчик на крыше Востока в 250–380 блоках от башни T2 (грань башни видна; бинокль мода — до 400 блоков)
uv run tools/trailer/perch.py $R --targets "3072.5,200,504.5" --rmin 250 --rmax 380 --min-y 90 --near 16
# p33: крыша, с которой видны верхи трёх башен (T2, T1, T4), каждая ближе 400 блоков
uv run tools/trailer/perch.py $R --targets "3072.5,255,504.5;3151.5,214,461.5;3154.5,234,566.5" --rmin 150 --rmax 320 --min-y 100 --near 10
```
Для каждой группы рельсов из третьей команды (по карте `--grid`) — луч из камеры плана 4 на её середину:
`uv run tools/trailer/ray.py $R 3116.5 68.1 306.5 <x> <y> <z> --fov 50`.
