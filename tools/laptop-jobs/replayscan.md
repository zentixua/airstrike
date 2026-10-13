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
   (2.10.2, 2.10.3), без `saves`, `flashback`, `logs`, `screenshots`, `crash-reports`, `audio.wav`. В каждой копии:
   - jar мода дня (`airstrike-*.jar` или `incoming-*.jar`) — из `mods/` в `day/`: `prod_client.py --no-copy` стирает
     `incoming-*.jar` в `mods/` и кладёт тот, что дан `--incoming-jar`;
   - jar разбора — в `mods/`;
   - `config/iris.properties`: `enableShaders=false`.
3. Копии повторов — `$W/replayscan/replays/`: готовые копии из `$W/epic-index/render/<версия>/flashback/replays/`
   (уже без пакетов Point Blank, путь DH — на копию); остальные — как `render_windows.replay_copy`:
   `patch_replay.py` (путь DH → `$W/release-media/dh-airstrike-overworld`; нет `distantHorizonPaths` — шаг не нужен),
   затем `strip_payloads.py <src> <dst> pointblank:mainheldsimplifiedstatebroadcastpacket` (`$W/epic-index`).
   Источник — `flashback/replays/` инстанса «Airstrike Pack».

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
`incoming:*`; в `payloads` есть `<airstrike|incoming>:blast`; `build` пишет число
строк. Иначе — первые строки `ERROR` и исключения из лога и `errors` из `meta.json`; один повтор запуска, не больше.

## Порядок
Только игры 06.10 (Артём: ранние версии мода и записи 12.10 без него не разбирать):
1. `2026-10-09T15_24_55` (2.10.3, 60 мин) — война 06.10 с Артёмом, MartinFrances и ClyuDan, самая интересная игра.
   Она же проба: через 10 минут — скорость по строкам `tick N of M` и память клиента; после конца — итог `build`,
   `seconds`, размер `out/$R`, роды ударов.
2. 06.10 09:59 (2.10.2), 06.10 16:31 (2.10.3).

## Отчёт
После каждого повтора — координатору одна строка: путь `replay.duckdb`, `complete`, тики и минуты разбора, роды
ударов, ошибки, если есть. `meta.json` и `report/*.csv` — одним архивом `replayscan-<повтор>.tar.gz` в файлы проекта.
Базы и таблицы остаются на ноутбуке (`$W/replayscan/out/`).
