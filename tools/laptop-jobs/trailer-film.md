# Задание ноутбуку: трейлер — планы из повторов с проверкой каждого кадра

Ветка `claude/project-thread-0hevmh` (zentixua/incoming), коммит — в сообщении координатора. Всё — в копии для съёмки и
своём рабочем дереве (как прежние съёмки трейлера): инстанс, сервер и миры Артёма не трогать; видеокарта — одна задача
и не во время его игры; тяжёлое — через `tools/laptop_job.sh`.

Как устроено (подробно — шапки `tools/trailer/film.sh`, `ReplayFilm`, `Coverage`, `FrameQa`):
- `film.sh record` — сценарий войны пишет каждый скрытый план повтором Flashback с журналом
  (`<папка>/replays/<план>.zip` и `.events.jsonl`: пути снарядов, удары, люди, кинокамера сценария по тикам, отметки);
  планы с интерфейсом (p07–p09, p14, p27, p33, p34, p40a, p43, p53) снимаются кадрами вживую, как раньше.
- `film.sh render` — по таблице `Coverage` каждый план выводится из своего повтора: камера сценария и запасные
  (штатив по кругу, погоня), повтор идёт вживую до начала плана (дым и огонь прежних ударов в кадре), Flashback выводит
  кадр на тик, каждый кадр проверяется по данным игры (мир построен, объект в кадре и его размер, блоки, дым, облака,
  мотор). Не прошёл — следующий кандидат. Итог: `<папка>/render/report.jsonl`, `render/best.txt` (папка плана для
  `edit.py --rec`, PASS или FAIL с причиной), в папке кандидата — `qa.jsonl` (строка на кадр) и `timeline.jsonl`.
  Перезапуск после падения продолжает с неснятых планов (по `best.txt`).

Моды: каталог модов копии для съёмки (`mod/run/film-war/minecraft/mods`: Flashback 0.39.10, flashback_neoforge_fixed,
Connector, Forgified Fabric API, Sodium 0.8.13, Iris, DH 3.3.3, Create, Sable, Aeronautics, IA, Point Blank) —
`INCOMING_MODS_DIR` (путь от `mod/`), без jar Incoming (его даёт сборка). Шейдерпак — аргумент `shaders`
(Complementary из инстанса, как у прошлых съёмок). Мир — свежая копия полной Zearth (`INCOMING_WORLD`), для частей
`nukeB`, `nukeC`, `icbm` — каждой своя свежая копия. Общие переменные:
```sh
export INCOMING_MODS_DIR=run/film-war/minecraft/mods INCOMING_SIZE=1920x1080 INCOMING_RENDER_DISTANCE=32 \
       INCOMING_TRAILER_DIR=film-war INCOMING_LANG=en_us INCOMING_FILM_CLOUDS=190
```
`INCOMING_FILM_CLOUDS` — низ слоя облаков шейдерпака (у Complementary около 190): линия взгляда через него считается
закрытой. Если облака шейдерпака в кадрах на другой высоте — поставить её. Прорисовка 32: объект дальше 0,8 прорисовки
(410 блоков) проверка считает скрытым туманом у её края, а цели телевиков — в 300–400 блоках (у плана 13 башня в 360).
Запись и вывод — с одной прорисовкой.

## 0. Карта всей Zearth (для мест камер вне города)
```sh
uv run tools/trailer/zmap.py <копия Zearth>/region zearth-map.png --scale 8
```
Прислать `zearth-map.png`, `zearth-map_h.npy`, `zearth-map.json` и вывод (самые высокие точки). Файлы — только во
временную передачу (лицензия Zearth: без распространения), после разбора они удаляются.

## 1. Проба на одном плане (p13)
```sh
INCOMING_WAR_PART=p13 tools/laptop_job.sh film-rec -- tools/trailer/film.sh record shaders
INCOMING_FILM_SHOTS=p13 tools/laptop_job.sh film-render -- tools/trailer/film.sh render shaders
```
Проверить в `mod/run/scenario/logs/latest.log`:
- нет `flawless frames Sodium не включились` (Sodium ждёт мира перед каждым кадром);
- `SCENARIO film verdict p13 own-35mm …` — PASS (камера сценария: за спиной жителя на крыше) или причины; `мир` (доля
  кадров с построенным миром) — 1.00; в строке `SCENARIO film export p13` в `people` — Ilya (житель на крыше);
- Point Blank: если открытие повтора падает на `pointblank:…broadcastpacket`, вырезать эти пакеты из копий повторов
  (`epic-index/strip_payloads.py`, как для превью) и положить скрипт в `tools/trailer/strip_payloads.py` этой ветки
  (коммит в ветку), шаг вырезания — в `film.sh` перед выводом;
- дальний рельеф DH в кадрах вывода (за 24 чанками): есть или нет.
Прислать: `render/report.jsonl`, `qa.jsonl` лучшего кандидата, лист 6 кадров лучшего (равномерно), выдержку лога
(`SCENARIO film`, ошибки).

## 2. Вся война
Если проба прошла (или причины понятны и не в съёмке): запись всех частей — каждая на свежей копии Zearth, папка одна:
```sh
INCOMING_WAR_PART=all   tools/laptop_job.sh film-rec -- tools/trailer/film.sh record shaders
INCOMING_WAR_PART=nukeB tools/laptop_job.sh film-rec -- tools/trailer/film.sh record shaders
INCOMING_WAR_PART=nukeC tools/laptop_job.sh film-rec -- tools/trailer/film.sh record shaders
INCOMING_WAR_PART=icbm  tools/laptop_job.sh film-rec -- tools/trailer/film.sh record shaders
```
`record.sh` чистит папку записи только у сценария `trailer`; у `film.sh` записи копятся в одной папке. Планы с текстом
игры по-русски — отдельной папкой, как раньше (`INCOMING_LANG=ru_ru`, `INCOMING_TRAILER_SHOTS` — планы с интерфейсом,
`tools/trailer/record.sh shaders`).

Потом вывод всех планов:
```sh
INCOMING_FILM_TRIES=2 tools/laptop_job.sh film-render -- tools/trailer/film.sh render shaders
```

## 3. Монтаж
```sh
uv run tools/trailer/edit.py --lang en --rec <папка планов с интерфейсом> $(awk '{print "--rec " $2}' mod/run/scenario/film-war/render/best.txt) --music <Juggernaut.mp3>
```
(та же команда с `--lang ru` и русской папкой интерфейса). Прислать: `dist/incoming-trailer-lite.mp4` (до 20 МиБ),
`render/report.jsonl`, `render/best.txt`, вывод `edit.py` (какие планы — заглушки).
