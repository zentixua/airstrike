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

## Проверка частей на новом коммите
Части `east` и `west` сразу (вторая — во втором рабочем дереве), потом `nuke`, каждая на свежей копии мастера
(`check-<часть>`, как в «Съёмка части»). `east` — самая долгая: в ней уличный бой (около сотни ботов, планы 26–30).
Прислать по каждой части:
- `render/report.md` и `render/best.txt`;
- из папки записи — `hud.jsonl` (живые планы), `grid.jsonl` (свет гаснет в кадре: p16, p38), `fight.jsonl` (бой
  глазами игрока: p27) — какие есть;
- строки `SCENARIO film verdict`, `SCENARIO film hud`, `SCENARIO film grid`, `SCENARIO film fight`,
  `SCENARIO film staging` и (у `east`) `TRAILER … повтор записан`; строк `SCENARIO film cast failed` быть не должно;
- ошибки из логов записи и вывода (`Exception`, `ERROR` мода) и время каждой части от начала записи до конца вывода.
