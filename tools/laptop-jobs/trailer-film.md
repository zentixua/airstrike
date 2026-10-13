# Задание ноутбуку: трейлер — запись и вывод войны на мастер-копии Zearth (film-war2)

Ветка `claude/project-thread-45f5nj` (zentixua/incoming), коммит — в сообщении координатора. Всё — в копии для съёмки и
своём рабочем дереве (как прежние съёмки трейлера): инстанс, сервер и миры Артёма не трогать; видеокарта — одна задача
и не во время его игры; тяжёлое — через `tools/laptop_job.sh`. Удалять на ноутбуке ничего нельзя без слова Артёма:
прежние папки (`film-war`, `film-war-en`, `film-war-ru`, копии `zearth-film`) остаются как есть.

Как устроено (подробно — шапки `tools/trailer/film.sh`, `ReplayFilm`, `Coverage`, `FrameQa`):
- `film.sh record` — сценарий войны пишет каждый скрытый план повтором Flashback с журналом
  (`<папка>/replays/<план>.zip` и `.events.jsonl`: пути снарядов, удары, люди, кинокамера сценария по тикам, отметки).
  Планы с интерфейсом (p07–p09, p14, p27, p33, p34, p40a, p43, p53) снимаются кадрами вживую в том же проходе,
  по-английски. p33 (удары в бинокль за 700 блоков) и p37b (закат с вершины) — дальше прорисовки: каждый их кадр
  проверяется вживую (`HudQa`), итог — строка `SCENARIO film hud …` и `<папка>/hud.jsonl`.
- `film.sh render` — каждый план выводится из своего повтора по таблице `Coverage`: камера сценария и запасные (штатив,
  погоня), повтор идёт вживую до начала плана, Flashback выводит кадр на тик, каждый кадр проверяется по данным игры
  (мир построен, объект в кадре и его размер, блоки, дым, облака, мотор, глубина LOD DH — в `qa.jsonl` поле `dh`:
  1 — своя, −1 — мира, план не проходит). Окно клиента — вдвое меньше кадра (`INCOMING_WINDOW`, ставит `film.sh`).
  Не прошёл — следующий кандидат. Итог: `<папка>/render/report.jsonl`, `render/best.txt` (папка плана для
  `edit.py --rec`, PASS или FAIL с причиной), `render/shots.txt`; в папке кандидата — `qa.jsonl` и `timeline.jsonl`.
  Перезапуск после падения продолжает с неснятых планов (по `best.txt`).

## 0. Мастер-копия Zearth
Мастер приходит с netcup (все чанки сгенерированы, LOD DH построены с той же карты, DH 3.3.3) вместе с sha256.
1. Сверить sha256 со значением из сообщения координатора; не сошлось — остановиться и сообщить.
2. Положить мастер в `mod/run/zearth-master` (папка мира: `level.dat`, `region/`, `data/` с базой DH) и сделать его
   только для чтения: `chmod -R a-w mod/run/zearth-master`. Сам мастер не открывать в игре.
3. Копия на часть — свежая, из мастера, в каталог миров сценария:
   ```sh
   cp -r --reflink=auto mod/run/zearth-master mod/run/scenario/saves/war2-<часть> && chmod -R u+w mod/run/scenario/saves/war2-<часть>
   ```
   Перед каждой копией — свободного места не меньше размера мастера и ещё 20 ГБ; не хватает — остановиться и
   сообщить. Копии частей держать до конца вывода: повтор читает LOD DH из `<копия>/data` (без неё вывод падает на
   `SQLITE_IOERR`).

Лицензия Zearth: файлы карты не пушить и не распространять; наружу — только кадры трейлера и текстовые итоги.

## 1. Запись
Моды — каталог копии для съёмки (`mod/run/film-war/minecraft/mods`: Flashback 0.39.10, flashback_neoforge_fixed,
Connector, Forgified Fabric API, Sodium 0.8.13, Iris, DH 3.3.3, Create, Sable, Aeronautics, IA, Point Blank, Almighty),
без jar Incoming (его даёт сборка). Шейдерпак — аргумент `shaders` (Complementary из инстанса). Общие переменные:
```sh
export INCOMING_MODS_DIR=run/film-war/minecraft/mods INCOMING_SIZE=1920x1080 INCOMING_RENDER_DISTANCE=32 \
       INCOMING_TRAILER_DIR=film-war2 INCOMING_LANG=en_us INCOMING_FILM_CLOUDS=320
```
`INCOMING_FILM_CLOUDS` — низ слоя облаков Complementary над всем действием (проверка кадра считает взгляд через слой
закрытым). Прорисовка 32 (410 блоков до тумана у края) — у записи и вывода одна.

Части по очереди, каждая на своей свежей копии мастера (п. 0.3), папка записи одна:
```sh
for P in all nukeB nukeC icbm p01e p37b; do
  cp -r --reflink=auto mod/run/zearth-master mod/run/scenario/saves/war2-$P && chmod -R u+w mod/run/scenario/saves/war2-$P
  INCOMING_WAR_PART=$P INCOMING_WORLD=war2-$P tools/laptop_job.sh film-rec -- tools/trailer/film.sh record shaders
done
```
После каждой части в `mod/run/scenario/logs/latest.log`:
- `SCENARIO film dh generator DISABLED PRE_EXISTING_ONLY`: генератор DH выключен, LOD — только из базы мастера и
  загруженных чанков (`record.sh` пишет это в `mod/run/scenario/config/DistantHorizons.toml`; без этой строки запись
  не начинается — прислать лог);
- нет `SCENARIO film cast failed` (боты войны: `record.sh` ставит `bots.max = 100`);
- `SCENARIO film staging …` — только «в тумане у края прорисовки» или «чанка цели у клиента ещё нет» (цели дальше
  прорисовки), без «закрыта» и «через слой облаков»;
- у p33 и p37b — `SCENARIO film hud … PASS` (или причины — прислать).
Каждый новый повтор — проверка разбора, как на netcup (снимок Flashback может сломаться на 0x08000000): не разобрался —
переписать его часть на новой копии мастера (прежнюю копию не удалять).

## 2. Вывод
```sh
INCOMING_FILM_TRIES=2 tools/laptop_job.sh film-render -- tools/trailer/film.sh render shaders
```
В логе:
- `SCENARIO film dh generator DISABLED PRE_EXISTING_ONLY`, как у записи;
- нет `Failed to handle packet`, `DecoderException` и `повтор закрылся` (отключение от повтора; есть — прислать
  стек целиком с 30 строками до него);
- в `qa.jsonl` выводимых кандидатов `"dh":1` в каждом кадре; перед каждым выводом текстуры DH пересоздаются в размер
  окна (строки `SCENARIO film dh textures 960x540 …`), строк «текстуры LOD DH размера вывода» нет или они редки
  (такой кандидат не выводится — прислать их).

## 3. Монтаж и отчёт
```sh
uv run tools/trailer/edit.py --lang en --rec mod/run/scenario/film-war2 $(awk '{print "--rec " $2}' mod/run/scenario/film-war2/render/best.txt) --music <Juggernaut.mp3>
python3 tools/trailer/report.py mod/run/scenario/film-war2/render --md mod/run/scenario/film-war2/render/report.md
```
Прислать: `dist/incoming-trailer-lite.mp4` (до 20 МиБ), `render/report.md`, `render/report.jsonl`, `render/best.txt`,
`hud.jsonl`, вывод `edit.py` (какие планы — заглушки), ошибки из логов записи и вывода (`Exception`, `ERROR` мода).
