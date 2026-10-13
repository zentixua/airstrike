# Задание ноутбуку: записи Артёма на Zearth Rec — превью TikTok его глазами

Коммит zentixua/incoming — в сообщении координатора; там же — какие записи брать и время моментов, если Артём назвал
время, а не ставил маркеры. Не во время игры Артёма: начинать, когда `pgrep -f 'instances/Airstrike Pack'` пусто; на
видеокарте — одна тяжёлая задача, клиент со всей сборкой — один. Инстанс, его миры и настройки только читать; повторы
и данные Distant Horizons — копиями. Пути — от `/mnt/data/projects/airstrike`, `JAVA_HOME` — `java-runtime-delta` из
Prism, `W=/mnt/data/projects/airstrike/mod/run/claude-work`, `T=$W/tiktok/rec`, `MC=$(python3 tools/paths.py MC)`.

Записи — с сервера Zearth Rec: сборка Open Skies 0.1.26, Incoming 3.0.1. Запись из отдельного инстанса с модом из main
(если он появится) — свой каталог игры (`$T/game-main`) с jar этого инстанса, остальное так же.

## 1. Сборка и каталог игры
```sh
git fetch origin <коммит> && git worktree add --detach $W/tiktok-rec-wt <коммит>; S=$W/tiktok-rec-wt/tools
$W/tiktok-rec-wt/mod/gradlew -p $S/replayscan build
python3 $S/prod_client.py --copy-only --dir $T/game        # моды без Incoming, config, шейдерпаки, options.txt
mkdir -p $T/game/day && cp "$MC"/mods/incoming-3.0.1.jar $T/game/day/
sha256sum $T/game/day/incoming-3.0.1.jar                  # c1b10e6e4f934917c06e97770f1c21aa65fb9e7565df7647ff59f10120adf506
cp $S/replayscan/build/libs/replayscan-1.0.0.jar $T/game/mods/
```
В `$T/game/config/DistantHorizons.toml`, раздел `[common.worldGenerator]`: `generatorPlan = "DISABLED"`,
`chunkGeneratorMode = "PRE_EXISTING_ONLY"`, как у клиентов трейлера (`tools/trailer/record.sh` в ветке трейлера):
Zearth — карта, генератор мира рисовал бы LOD леса и моря поверх неё.

## 2. Копии повторов
Повторы — `"$MC"/flashback/replays/<имя>.zip` из сообщения координатора. LOD — то, что видел Артём: каталог из
`distantHorizonPaths` в `metadata.json` повтора (`unzip -p <zip> metadata.json`), копией в `$T/dh/`
(`cp -a --reflink=auto`; один каталог у нескольких повторов — одна копия). Затем, как в `replayscan.md`:
```sh
python3 $W/epic-index/patch_replay.py "$MC/flashback/replays/$N.zip" $T/replays/$N.dh.zip $T/dh/<каталог>
python3 $W/epic-index/strip_payloads.py $T/replays/$N.dh.zip $T/replays/$N.zip pointblank:mainheldsimplifiedstatebroadcastpacket
rm $T/replays/$N.dh.zip
```
Нет `distantHorizonPaths` — шаг `patch_replay.py` не нужен (сказать в отчёте: тогда в кадре нет LOD).

## 3. Разбор (видеокарта не нужна)
На время разбора в `$T/game/config/iris.properties` — `enableShaders=false`. На каждый повтор:
```sh
timeout -k 60 2h $S/laptop_job.sh scan-$N -- python3 $S/prod_client.py --no-copy --dir $T/game \
  --incoming-jar $T/game/day/incoming-3.0.1.jar --size 640x360 --jvm=-Xmx8g --prop replayscan.replay=$T/replays/$N.zip \
  --prop replayscan.out=$T/out/$N --prop replayscan.viewers=players --prop replayscan.every=2
uv run $S/replayscan/replaydb.py build $T/out/$N
```
Удачно — как в `replayscan.md`: `complete: true`, `gaps: 0`, в `payloads` есть `incoming:blast`. Иначе — первые
`ERROR` и исключения лога, `errors` из `meta.json`; один повтор запуска.

## 4. Моменты и планы
```sh
uv run $S/clips/moments.py $T/out/*/replay.duckdb --out $T/clips [--at <время из сообщения>]...
uv run $S/clips/plan.py $T/clips/candidates.json --all pov
```
Запись с отметками (маркеры Flashback «Создать маркер», время `--at`) даёт только их, глазами Артёма; запись без
отметок — лучшие отрезки глазами живых игроков. Раздел «6. Глазами игроков» из `$T/clips/candidates.md` — в отчёт.

## 5. Съёмка (видеокарта)
`enableShaders=true` в `$T/game/config/iris.properties`; шейдерпак — как у Артёма, у него выключены — Complementary
Reimagined из `shaderpacks/`. Окно — не размера кадра (по умолчанию 1280×720). На каждую строку `$T/clips/film/runs.json`:
```sh
$S/laptop_job.sh tiktok-rec -- python3 $S/prod_client.py --no-copy --dir $T/game \
  --incoming-jar $T/game/day/incoming-3.0.1.jar --jvm=-Xmx12g --prop replayscan.film=<plan из runs.json>
```
Успех: в `$T/game/logs/latest.log` — `Film: done`; у каждого плана — `Film: the replay viewer spectates <игрок> on the
replay server` и `Film: verdict <план> <камера>: PASS`, в его `qa.jsonl` — `"dh":1`. Не прошедший план не доводить.

## 6. Монтаж и выдача
```sh
uv run $S/clips/edit.py $T/clips/film --out $T/clips/out --soon
```
Ролики `$T/clips/out/pov-<n>.mp4`, подписи `pov-<n>.txt` и `report.md` — в файлы проекта, `tiktok-rec/` (до 20 МиБ;
больше — H.264 CRF 20, звук как есть). Прислать: `report.md`, строки `Film: verdict` и `spectates`, ошибки
(`Exception`, `ERROR`) из логов разбора и съёмки, по каждому ролику число строк `step`, `incoming:` и `pointblank:` в
его `sounds.jsonl`.
