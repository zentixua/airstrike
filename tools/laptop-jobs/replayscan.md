# Задание ноутбуку: повторы Flashback — в базы (tools/replayscan)

Ветка `claude/project-thread-a9cwbq` (zentixua/incoming), коммит — в сообщении координатора. Цель — разобрать повторы
игр самой игрой (`tools/replayscan/README.md`) в таблицы и базу DuckDB: события, пути снарядов, удары по земле, в
воздухе и перехваты, видимость глазами всех игроков. Базы нужны треду «Превью TikTok» для выбора моментов.

Не трогать: инстанс Артёма, его миры и настройки; оригиналы повторов только читать, разбирать — копии. Не во время
игры Артёма (`pgrep -af java` — нет клиента инстанса «Airstrike Pack»). Клиент разбора — со всей сборкой (около
10 ГБ): одновременно с другим клиентом со всей сборкой (шаги трейлера `record`, `render`) не идёт; рядом с `cut`
(монтаж) — можно. Видеокарта разбору не нужна: он не рисует (шейдеры в его копии выключены).

Пути — от `/mnt/data/projects/airstrike`, `W=mod/run/claude-work`, `JAVA_HOME` — `java-runtime-delta` из Prism.

## Подготовка (один раз)
1. Рабочее дерево и сборка:
   ```sh
   git fetch origin claude/project-thread-a9cwbq
   git worktree add --detach $W/replayscan-wt <коммит>
   $W/replayscan-wt/mod/gradlew -p $W/replayscan-wt/tools/replayscan build   # → tools/replayscan/build/libs/replayscan-1.0.0.jar
   ```
2. Каталоги игры — `$W/replayscan/game/<версия>`: копия (`cp -a --reflink=auto`) `$W/epic-index/render/<версия>`
   (2.5.0, 2.8.0, 2.10.0, 2.10.2, 2.10.3) и для записей 12.10 — `$W/release-media/prod` (`game/3.0`), без `saves`,
   `flashback`, `logs`, `screenshots`, `crash-reports`, `audio.wav`. В каждой копии:
   - jar мода дня (`airstrike-*.jar` или `incoming-*.jar`) — из `mods/` в `day/`: `prod_client.py --no-copy` стирает
     `incoming-*.jar` в `mods/` и кладёт тот, что дан `--incoming-jar`;
   - jar разбора — в `mods/`;
   - `config/iris.properties`: `enableShaders=false`.
   Для 12.10 нужен jar Incoming, с которым шла запись (в `release-media/prod/mods`, если его не меняли после записи;
   иначе — выпуск v3.0.1). Набора 2.10.1 нет: повтор 05.10 15:45 — в `game/2.10.0` с jar выпуска v2.10.1
   (github.com/zentixua/incoming, наш репозиторий); не выходит — пропустить и сказать.
3. Копии повторов — `$W/replayscan/replays/`: готовые копии из `$W/epic-index/render/<версия>/flashback/replays/`
   (уже без пакетов Point Blank, путь DH — на копию); остальные — как `render_windows.replay_copy`:
   `patch_replay.py` (путь DH → `$W/release-media/dh-airstrike-overworld`; нет `distantHorizonPaths` — шаг не нужен),
   затем `strip_payloads.py <src> <dst> pointblank:mainheldsimplifiedstatebroadcastpacket` (`$W/epic-index`).
   Источники: повторы Артёма — `flashback/replays/` инстанса «Airstrike Pack», записи 12.10 —
   `$W/release-media/prod/flashback/replays/`, повторы ENOTzRPG — `$W/enot-2/`.

## Разбор одного повтора
```sh
cd $W/replayscan
V=<версия>; R=<имя повтора без .zip>; G=$PWD/game/$V; T=$PWD/../replayscan-wt/tools
timeout -k 60 4h $T/laptop_job.sh scan-$R -- python3 $T/prod_client.py --no-copy --dir $G --incoming-jar $G/day/<jar> \
  --size 640x360 --jvm=-Xmx8g --prop replayscan.replay=$PWD/replays/$R.zip --prop replayscan.out=$PWD/out/$R \
  --prop replayscan.viewers=players --prop replayscan.every=2
uv run $T/replayscan/replaydb.py build out/$R
uv run $T/replayscan/replaydb.py --aspect 0.5625 report out/$R/replay.duckdb
uv run $T/replayscan/replaydb.py sql out/$R/replay.duckdb "SELECT place, count(*) FROM impact_kinds GROUP BY place"
rm -f $G/audio.wav
```
Ход — строки `Replay scan: tick N of M` в `$G/logs/latest.log` (раз в 30 с); клиент выходит сам. Повтор встал на
5 минут — разбор кончается сам (`complete: false`).

**Удачно:** в `out/$R/meta.json` `complete: true`, `gaps: 0`; в `errors` нет пакетов игры и пакетов `airstrike:*` или
`incoming:*`; в `payloads` есть `<airstrike|incoming>:blast` (кроме 05.10 22:18 — там ударов нет); `build` пишет число
строк. Иначе — первые строки `ERROR` и исключения из лога и `errors` из `meta.json`; один повтор запуска, не больше.

## Порядок
0. Проба: 03.10 21:18 (2.8.0, 8 мин, шесть игроков). Прислать: итог `build`, `seconds` и `last_tick` (скорость),
   наибольшую память клиента, размер `out/$R`, роды ударов. По ним — решение о двух разборах сразу.
1. Военные повторы, из которых берутся моменты превью: 06.10 09:59 (2.10.2), 03.10 20:47 (2.8.0), 09.10 15:24 (2.10.3), три записи 12.10 (19_40_18, 20_36_54, 20_44_14;
   `game/3.0`), 04.10 17:38 (2.10.0), 02.10 17:38 (2.5.0), 06.10 16:31 (2.10.3).
2. Остальные — только по запросу треда «Превью TikTok» (решение Артёма: сначала военные): 02.10 17:26 и 18:41
   (2.5.0); 04.10 18:16, 19:38, 20:29 (2.10.0); 05.10 22:18 и 23:34 (2.10.2); 06.10 22:13 (2.10.3); 05.10 15:45
   (2.10.1); повторы ENOTzRPG — версия по дню записи (`footage.md`).

## Отчёт
После каждого повтора — координатору одна строка: путь `replay.duckdb`, `complete`, тики и минуты разбора, роды
ударов, ошибки, если есть. `meta.json` и `report/*.csv` — одним архивом `replayscan-<повтор>.tar.gz` в файлы проекта.
Базы и таблицы остаются на ноутбуке (`$W/replayscan/out/`).
