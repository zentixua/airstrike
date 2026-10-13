# Задание ноутбуку: трейлер — перезапись исправленных планов и их вывод (film-war3)

Ветка `claude/project-thread-xnjd1k` (zentixua/incoming), коммит — в сообщении координатора. Всё — в рабочем дереве
съёмки (как прежние задания): инстанс, сервер и миры Артёма не трогать; видеокарта — одна задача и не во время его игры
(`pgrep -f 'instances/Airstrike Pack'` пусто — начинать без вопросов); тяжёлое — через `tools/laptop_job.sh`. Удалять
на ноутбуке ничего нельзя без слова Артёма: `film-war2` и её миры `war2-<часть>` остаются как есть — новая запись идёт
в свою папку `film-war3`, монтаж берёт её планы поверх `film-war2`.

Что изменилось (подробно — `.claude/rules/trailer.md`): p12, p01d — поток шахедов ровно над камерой, план — с шахеда,
вошедшего в кадр; p13 — житель у края кадра; p15, p18 — FPV у конца пути смотрит, куда летел; p19b — ракеты потоком
над крышей; p23b — ракета, только когда под прицелом наводчика дирижабль; p29 — место бойца, где из-за плеча открыто
небо; p32 — «Град» батареей в улицу впереди FPV; p38, p39b — рой батареей навстречу камере, «Град» p38 к удару по
узлу; p01e — объект плана — первый удар в окне (только вывод). Новое в логе записи: `SCENARIO film camera <план>`
(камера сценария в блоках) и `TRAILER airship: не дождались: дирижабль под прицелом наводчика` — прислать, если есть.

## 1. Запись
Одна часть `retake` на свежей копии мастера: сцены всех исправленных планов в порядке части `all` (город к плану уже
горит, как в `all`), пишутся только названные планы. Свободного места перед копией — не меньше размера мастера и ещё
20 ГБ; не хватает — остановиться и сообщить.
```sh
export INCOMING_MODS_DIR=run/film-war/minecraft/mods INCOMING_SIZE=1920x1080 INCOMING_RENDER_DISTANCE=32 \
       INCOMING_TRAILER_DIR=film-war3 INCOMING_LANG=en_us INCOMING_FILM_CLOUDS=320
cp -r --reflink=auto mod/run/zearth-master mod/run/scenario/saves/war3-retake && chmod -R u+w mod/run/scenario/saves/war3-retake
INCOMING_WAR_PART=retake INCOMING_WORLD=war3-retake \
INCOMING_TRAILER_SHOTS=p12,p13,p15,p18,p19b,p23b,p01d,p29,p32,p38,p39b \
  tools/laptop_job.sh film-rec -- tools/trailer/film.sh record shaders
```
В `mod/run/scenario/logs/latest.log`, как в прошлых записях: `SCENARIO film dh generator DISABLED PRE_EXISTING_ONLY`,
нет `SCENARIO film cast failed`; прислать все строки `SCENARIO film staging`, `SCENARIO film camera`, `TRAILER airship`
и `TRAILER … момент не наступил`. Каждый новый повтор — проверка разбора, как раньше: не разобрался — переписать часть
на новой копии мастера (прежнюю не удалять).

## 2. Журналы
По каждому записанному плану — журнал текстом (без игры), одним файлом:
```sh
W3=mod/run/scenario/film-war3
for S in p12 p13 p15 p18 p19b p23b p01d p29 p32 p38 p39b; do
  echo "== $S"; uv run tools/trailer/journal.py $W3/replays/$S.events.jsonl --region mod/run/scenario/saves/war3-retake/region
done > $W3/journal.txt 2>&1
```

## 3. Вывод
Сразу после записи, с настройками пака по умолчанию: исправленные планы из `film-war3` и p01e из повтора `film-war2`
в свою папку вывода (`render-p01e`: прежний вывод `film-war2/render` не трогается).
```sh
INCOMING_TRAILER_DIR=film-war3 INCOMING_FILM_TRIES=2 INCOMING_FILM_SHOTS=p12,p13,p15,p18,p19b,p23b,p01d,p29,p32,p38,p39b \
  tools/laptop_job.sh film-render -- tools/trailer/film.sh render shaders
INCOMING_TRAILER_DIR=film-war2 INCOMING_FILM_OUT=render-p01e INCOMING_FILM_TRIES=2 INCOMING_FILM_SHOTS=p01e \
  tools/laptop_job.sh film-render -- tools/trailer/film.sh render shaders
```
В логе — то же, что в прошлом выводе: `dh generator DISABLED PRE_EXISTING_ONLY`; нет `Failed to handle packet`,
`DecoderException`, `повтор закрылся` (есть — стек целиком с 30 строками до него); строки `SCENARIO film heap` и
`SCENARIO film restart` — прислать; `"dh":1` в `qa.jsonl` выводимых кандидатов.

## 4. Монтаж и отчёт
Планы `film-war2` (с туманом p03 — папка выбранного варианта `c2-alt110-d3.05-dist200`), поверх — p01e из `render-p01e`
и планы `film-war3`. Строки `best.txt` с «-» вместо папки — план не снят (причина в той же строке), в `--rec` их нет.
```sh
W2=mod/run/scenario/film-war2 W3=mod/run/scenario/film-war3 FOG=$W2/render-fog-<папка варианта c2>
uv run tools/trailer/edit.py --lang en --rec $W2 $(awk '$2 != "-" {print "--rec " $2}' $W2/render/best.txt) \
  $(awk '$1 == "p03" && $2 != "-" {print "--rec " $2}' $FOG/best.txt) \
  $(awk '$2 != "-" {print "--rec " $2}' $W2/render-p01e/best.txt) \
  --rec $W3 $(awk '$2 != "-" {print "--rec " $2}' $W3/render/best.txt) --music <Juggernaut.mp3>
python3 tools/trailer/report.py $W3/render --md $W3/render/report.md
python3 tools/trailer/report.py $W2/render-p01e --md $W2/render-p01e/report.md
```
Прислать: `dist/incoming-trailer-lite.mp4` (до 20 МиБ), оба `report.md`, `film-war3/render/best.txt`,
`film-war2/render-p01e/best.txt`, `film-war3/journal.txt`, вывод `edit.py` (какие планы — заглушки), строки из п. 1
и п. 3, ошибки из логов записи и вывода (`Exception`, `ERROR` мода).
