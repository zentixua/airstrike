# Задание ноутбуку: трейлер — одна пересъёмка по замечаниям Артёма (film-war4)

Ветка `claude/project-thread-xnjd1k` (zentixua/incoming), коммит — в сообщении координатора. Всё — в рабочем дереве
съёмки (как прежние задания): инстанс, сервер и миры Артёма не трогать; видеокарта — одна задача: начинать, когда
кончилось задание TikTok (clips3) и нет игры Артёма (`pgrep -f 'instances/Airstrike Pack'` пусто), без вопросов;
тяжёлое — через `tools/laptop_job.sh`. Удалять на ноутбуке ничего нельзя без слова Артёма: `film-war2`, `film-war3` и
их миры остаются как есть — новая запись идёт в свою папку `film-war4`, монтаж берёт её поверх `film-war2`.

Что изменилось (подробно — `.claude/rules/trailer.md`, `entity.md`):
- Снаряды и зенитная ракета у клиента (и в повторе) идут по месту на тик (`ArrivalQueue`): погоня больше не дёргается,
  модель с первого кадра смотрит по курсу.
- Камера вывода «как снимал человек» (`Operator`): плавный разгон и торможение, руки, толчок от каждого взрыва по
  приходу волны, сильнее вблизи; FPV — как эталон. Тряска мода при выводе выключена (кроме ядерных планов). МБР —
  ещё и следящей камерой сбоку длинным фокусом (кандидаты `track-…`). Камера сценария как есть — запасной `-raw`.
- Боты поворачиваются и ходят как люди (`Neck`); пуски с установок вразнобой; p04 — боты уже идут; p30 — шахед не в
  фонтан; p25 — своя камера из-за плеча.
- Журнал живых планов (`timeline.jsonl`) дописывается, а не стирается каждой частью; отметку `front` у планов из
  повтора ставит вывод. Поэтому все живые планы переснимаются в этой же записи.

## 1. Вывод старых повторов новой камерой (сразу, первым)
Планы, сцены которых не переснимаются (пролёт p03, хребет p21, ЗРК p22, FPV p01a/p01b, аэродром p24, МБР p42, волна
p46–p49), — заново из повторов `film-war2` в свою папку `render-human` (прежний `render` не трогается). Это и первая
проверка новой камеры: итог прислать сразу, не дожидаясь записи.
```sh
export INCOMING_MODS_DIR=run/film-war/minecraft/mods INCOMING_SIZE=1920x1080 INCOMING_RENDER_DISTANCE=32 \
       INCOMING_LANG=en_us INCOMING_FILM_CLOUDS=320
INCOMING_TRAILER_DIR=film-war2 INCOMING_FILM_OUT=render-human INCOMING_FILM_TRIES=2 \
INCOMING_FILM_SHOTS=p21,p22a,p22b,p01a,p01b,p24,p24b,p24c,p42,p46,p47,p48,p49 \
  tools/laptop_job.sh film-render -- tools/trailer/film.sh render shaders
```
p03 — тем же набором тумана, что вывод `render-fog-c2-alt110-d3.05-dist200` (строка `INCOMING_SHADER_OPTS` варианта
c2 из прежнего задания), в папку `render-fog-c2-human`:
```sh
INCOMING_TRAILER_DIR=film-war2 INCOMING_SHADER_OPTS="<набор c2>" INCOMING_FILM_OUT=render-fog-c2-human \
INCOMING_FILM_SHOTS=p03 INCOMING_FILM_TRIES=2 tools/laptop_job.sh film-render -- tools/trailer/film.sh render shaders
python3 tools/trailer/report.py mod/run/scenario/film-war2/render-human --md mod/run/scenario/film-war2/render-human/report.md
```
Набора c2 не нашлось — p03 пропустить (монтаж возьмёт прежний вывод) и сказать.
Прислать: `render-human/report.md` (с таблицей движения камеры), `render-human/best.txt`, `render-fog-c2-human/best.txt`,
строки `SCENARIO film heap`/`restart`, ошибки (как в п. 3).

## 2. Запись
Одна часть `retake` на свежей копии мастера: все сцены с ботами, пусками с установок и живыми планами в порядке части
`all` (рассвет на площади p04, первый удар и тревога, батарея и завод, дирижабль, p25, p01d, улицы, сквозь огонь, ночь,
ядерный удар). `INCOMING_TRAILER_SHOTS` не задавать: пишутся все планы этих сцен, живые — тоже. Свободного места перед
копией — не меньше размера мастера и ещё 20 ГБ; не хватает — остановиться и сообщить (удалять нельзя).
```sh
cp -r --reflink=auto mod/run/zearth-master mod/run/scenario/saves/war4-retake && chmod -R u+w mod/run/scenario/saves/war4-retake
INCOMING_TRAILER_DIR=film-war4 INCOMING_WAR_PART=retake INCOMING_WORLD=war4-retake \
  tools/laptop_job.sh film-rec -- tools/trailer/film.sh record shaders
```
В `mod/run/scenario/logs/latest.log`, как в прошлых записях: `SCENARIO film dh generator DISABLED PRE_EXISTING_ONLY`,
нет `SCENARIO film cast failed`; прислать все строки `SCENARIO film staging`, `SCENARIO film camera`, `SCENARIO film hud`,
`SCENARIO film fight`, `SCENARIO film grid`, `TRAILER …` (момент не наступил, не дождались). Каждый новый повтор —
проверка разбора, как раньше: не разобрался — переписать часть на новой копии мастера (прежнюю не удалять).

## 3. Вывод записи
Перед выводом — рабочее дерево съёмки на коммит из последнего сообщения координатора (не раньше `22b2cfd`; запись,
если ещё идёт, не прерывать — переход только между шагами) и сборка заново. В `22b2cfd` исправлена очередь мест
снаряда у клиента: при сервере медленнее 20 тиков в секунду снаряд у клиента (и в повторе) уходил по прямой с пути;
пакеты в повторах те же, место в выводе — уже по серверу. Из лога записи прислать все строки `Can't keep up`
(время и сколько мс): где они, места в журнале могли разойтись с выводом — проверка кадров это ловит.
Сразу после записи, с настройками пака по умолчанию, все планы `film-war4`:
```sh
INCOMING_TRAILER_DIR=film-war4 INCOMING_FILM_TRIES=2 tools/laptop_job.sh film-render -- tools/trailer/film.sh render shaders
python3 tools/trailer/report.py mod/run/scenario/film-war4/render --md mod/run/scenario/film-war4/render/report.md
```
В логе — то же, что в прошлом выводе: `dh generator DISABLED PRE_EXISTING_ONLY`; нет `Failed to handle packet`,
`DecoderException`, `повтор закрылся` (есть — стек целиком с 30 строками до него); строки `SCENARIO film heap` и
`SCENARIO film restart` — прислать; `"dh":1` в `qa.jsonl` выводимых кандидатов.

p01a и p42 из `film-war2` — ещё раз, с местом снаряда, как его показывает повтор (`INCOMING_FILM_SHOWN=1`: журналы
`film-war2` записаны до очереди мест, камера сценария вела рывки журнала — в `render-human` дрожь объекта 14,4 и 4,6 %
полукадра/кадр² при ≤ 1,6 у остальных), в свою папку `render-human2`:
```sh
INCOMING_TRAILER_DIR=film-war2 INCOMING_FILM_OUT=render-human2 INCOMING_FILM_SHOTS=p01a,p42 INCOMING_FILM_SHOWN=1 \
INCOMING_FILM_TRIES=2 tools/laptop_job.sh film-render -- tools/trailer/film.sh render shaders
python3 tools/trailer/report.py mod/run/scenario/film-war2/render-human2 --md mod/run/scenario/film-war2/render-human2/report.md
```
Прислать `render-human2/report.md` и `qa.jsonl` выведенных кандидатов p01a и p42 из `render-human` и `render-human2`.

Журналы — по каждому плану, где итог вывода (п. 3) не PASS, текстом одним файлом:
```sh
W4=mod/run/scenario/film-war4
for S in $(awk '$3 != "PASS" {print $1}' $W4/render/best.txt); do
  echo "== $S"; uv run tools/trailer/journal.py $W4/replays/$S.events.jsonl --region mod/run/scenario/saves/war4-retake/region
done > $W4/journal.txt 2>&1
python3 tools/trailer/drift.py $W4 > $W4/drift.txt
```
`drift.txt` — у каких планов снаряд в журнале записи ушёл с пути (очередь мест до `22b2cfd` при отставании сервера:
в записи 86 строк `Can't keep up`); камеры таких планов ставились по ушедшему месту, вывод показывает настоящее.

## 3в. p34 заново (запись одной сцены)
p34 (с борта ракеты, 1:07) не снимался ни разу: камера снаряда включалась до пуска, полёта ещё не было, план ждал
момент и шёл заглушкой. Исправлено, сцена `scene:p34` — одна p34 у поста бинокля p33; рабочее дерево — на коммит из сообщения координатора и сборка,
после вывода `render-human2`, до монтажа. Свободного места — как в п. 2.
```sh
cp -r --reflink=auto mod/run/zearth-master mod/run/scenario/saves/war5-p34 && chmod -R u+w mod/run/scenario/saves/war5-p34
INCOMING_TRAILER_DIR=film-war5 INCOMING_WAR_PART=scene:p34 INCOMING_WORLD=war5-p34 \
  tools/laptop_job.sh film-rec -- tools/trailer/film.sh record shaders
```
В логе: нет `TRAILER p34: момент не наступил`; есть отметки `video` и `gone` у p34 в `film-war5/timeline.jsonl`
(`grep '"shot":"p34"' mod/run/scenario/film-war5/timeline.jsonl | grep -c '"type":"frame"'` — кадры). Прислать строки
`TRAILER p34` и `SCENARIO film` из лога.

## 4. Монтаж
Дубль плана — новейший прошедший проверку (PASS), а если у плана таких нет — новейший какой есть. Порядок дублей:
`film-war2` (`render`, p03 с туманом c2, `render-human`, `render-human2`), затем живые планы и вывод `film-war4`.
`render-human2` (p01a и p42) — если в его отчёте PASS и дрожь объекта меньше, чем в `render-human`; иначе оба
`$(rec $W2/render-human2/best.txt …)` убрать. Строки `best.txt` с «-» вместо папки — план не снят (причина в той же
строке), в `--rec` их нет.
```sh
W2=mod/run/scenario/film-war2 W4=mod/run/scenario/film-war4
# rec <best.txt> [план] [итог]: папки дублей; второй проход — только PASS, он поверх первого
rec() { [ -f "$1" ] && awk -v s="${2:-}" -v v="${3:-}" '$2 != "-" && (s == "" || $1 == s) && (v == "" || $3 == v) {printf "--rec %s ", $2}' "$1"; }
FOG1=$W2/render-fog-c2-alt110-d3.05-dist200/best.txt FOG2=$W2/render-fog-c2-human/best.txt
ARGS="--lang en --rec $W2 $(rec $W2/render/best.txt) $(rec $FOG1 p03) $(rec $FOG2 p03) \
  $(rec $W2/render-human/best.txt) $(rec $W2/render-human2/best.txt) --rec $W4 $(rec $W4/render/best.txt) \
  $(rec $W2/render/best.txt '' PASS) $(rec $FOG1 p03 PASS) $(rec $FOG2 p03 PASS) \
  $(rec $W2/render-human/best.txt '' PASS) $(rec $W2/render-human2/best.txt '' PASS) $(rec $W4/render/best.txt '' PASS) \
  $([ -f mod/run/scenario/film-war5/timeline.jsonl ] && echo --take p34=mod/run/scenario/film-war5) --music <Juggernaut.mp3>"
mkdir -p dist && echo "uv run tools/trailer/edit.py $ARGS" > dist/incoming-trailer-edit.sh
uv run tools/trailer/edit.py $ARGS 2>&1 | tee dist/incoming-trailer-edit.log
```
Живой план (кадры в `timeline.jsonl` папки записи, итог — таблицы hud/grid/fight отчёта) берётся из `film-war4`; если
там он хуже, чем в `film-war2` (p34: «момент не наступил»), — `--take p34=$W2` в конце строки: дубль плана из этой
папки, что бы ни дали `--rec`.
`dist/incoming-trailer-edit.sh` — монтаж как есть: правка по замечанию утром — эта строка с `--take <план>=<папка дубля>` в конце.
Звук пуска p01c (монтаж прежде писал «p01c: no sound:rocket.launch»): какие звуки есть в журнале выведенного p01c —
```sh
grep -o '"event":"[^"]*"' "$(awk '$1 == "p01c" {print $2}' $W4/render/best.txt)/timeline.jsonl" | sort | uniq -c | sort -rn | head -20
```

Прислать: `dist/incoming-trailer-lite.mp4` (до 20 МиБ), `dist/incoming-trailer-cuts.md` (время в трейлере → план,
окно плана, откуда дубль), `dist/incoming-trailer-edit.sh`, `film-war4/render/report.md`, `film-war4/render/best.txt`,
`film-war4/journal.txt`, `film-war4/drift.txt`, `dist/incoming-trailer-edit.log`, счёт звуков p01c, строки из п. 2 и п. 3,
ошибки из логов записи и вывода (`Exception`, `ERROR` мода).

## 5. Спасение планов (видеокарта; можно сразу, пока п. 4 считает монтаж на процессоре)
Рабочее дерево съёмки — на коммит из сообщения координатора и сборка. Что выводится:
- `film-war2/render-human3` — планы, которые в монтаж идут из `film-war2/render` (в `film-war4` нет PASS или нет
  объекта): p11, p26, p31, p39a, p39d. Тот вывод — до `181d3ab`: камера без «человека» и тряска мода 5° ~10 Гц у
  взрывов («эпилептика» у p26 по замечанию Артёма). Без `INCOMING_FILM_SHOWN`: с ней p01a в `render-human2` не прошёл.
- `film-war4/render-metro` — метро p15, p18: у всех кандидатов «камера в блоках» — проверка требовала полблока воздуха
  вокруг камеры, а лестница метро шириной в блок; теперь 0,2 блока (ближняя плоскость кадра — 0,05).
```sh
INCOMING_TRAILER_DIR=film-war2 INCOMING_FILM_OUT=render-human3 INCOMING_FILM_TRIES=2 \
INCOMING_FILM_SHOTS=p11,p26,p31,p39a,p39d tools/laptop_job.sh film-render -- tools/trailer/film.sh render shaders
INCOMING_TRAILER_DIR=film-war4 INCOMING_FILM_OUT=render-metro INCOMING_FILM_TRIES=2 \
INCOMING_FILM_SHOTS=p15,p18 tools/laptop_job.sh film-render -- tools/trailer/film.sh render shaders
for R in film-war2/render-human3 film-war4/render-metro; do
  python3 tools/trailer/report.py mod/run/scenario/$R --md mod/run/scenario/$R/report.md
done
```
Кандидат дольше 15 минут при оценке ниже 0,1 (как p31 в п. 3) — снять этот план и идти дальше без него, сказать.
Прислать оба `report.md` и `best.txt`, строки `SCENARIO film heap`/`restart`, ошибки — как в п. 3.

## 6. Монтаж из всего снятого
После п. 5 и п. 7 и, если оно было этой ночью, вывода камер по сцене (`render-planned`, задание `shot-trailer.md`).
`tools/trailer/cut.sh` собирает строку п. 4 сам: у плана — PASS из самого сильного вывода (`CUT_RENDERS` в скрипте:
`render-planned` — слабее всех, `render-human3` и `render-metro` — сильнее всех), нет PASS — не прошедший, живые планы —
из `film-war2`, `film-war4`, `film-war5`. Прежний монтаж п. 4 не трогается — новый в свои файлы:
```sh
tools/trailer/cut.sh --lang en --music <Juggernaut.mp3> --out dist/incoming-trailer-v2.mp4
```
Прислать: `dist/incoming-trailer-v2-lite.mp4`, `dist/incoming-trailer-v2-cuts.md`, `dist/incoming-trailer-edit.sh`,
`dist/incoming-trailer-edit.log`.
В логе монтажа строки `! <план>: в … — беру …` — план взят из более слабого дубля, где есть момент склейки (сход у
пусков, фронт волны у p48 и p50): прислать их отдельно.

## 7. Батарея заново, p18, концовка (видеокарта; после TikTok clips8, до п. 6)
Рабочее дерево съёмки — на коммит из сообщения координатора и сборка (между шагами, не во время записи или вывода).
Что исправлено:
- Пуски p10a–c, p20, p01c, p35: план начинался, как только снаряд вставал на направляющую, а сходит он через 0,6–1 с; у
  «Града» в p20 и p01c — только через минуту (трубы перезаряжаются после залпа p10b, а приказ, пока залп идёт, пусковая
  отклоняет). В монтаже p10a и p35 кончались до первого схода, в p01c не сошло ни одной ракеты. Теперь окно — с поджига,
  p20 ждёт перезарядки труб (~минута записи), p01c снимается в том же залпе изнутри пакета. Сцена `scene:battery`,
  пишутся только эти планы и p11 (погоня за шахедом p10c: в журнале `film-war2` до очереди мест дрожь объекта 16 %).
- p18: камера сценария проходит в 0,1 блока над ступенью лестницы метро — у вывода две запасные той же камеры выше на
  0,25 и 0,4 (`own-…-up25`, `own-…-up40`).
- p48 и p50 из `film-war2` — заново, с отметкой фронта: в `film-war4` волна в p50 через 1,75 с от начала плана, монтажу
  нужно 5,76 с (волна на последний удар трека). Монтаж берёт тот дубль, где фронта ждать дольше.
Свободного места — как в п. 2.
```sh
cp -r --reflink=auto mod/run/zearth-master mod/run/scenario/saves/war6-battery && chmod -R u+w mod/run/scenario/saves/war6-battery
INCOMING_TRAILER_DIR=film-war6 INCOMING_WAR_PART=scene:battery INCOMING_WORLD=war6-battery \
INCOMING_TRAILER_SHOTS=p10a,p10b,p10c,p11,p20,p01c,p35 tools/laptop_job.sh film-rec -- tools/trailer/film.sh record shaders
INCOMING_TRAILER_DIR=film-war6 INCOMING_FILM_TRIES=2 INCOMING_FILM_SHOTS=p10a,p10b,p10c,p11,p20,p01c,p35 \
  tools/laptop_job.sh film-render -- tools/trailer/film.sh render shaders
INCOMING_TRAILER_DIR=film-war4 INCOMING_FILM_OUT=render-metro2 INCOMING_FILM_TRIES=2 INCOMING_FILM_SHOTS=p18 \
  tools/laptop_job.sh film-render -- tools/trailer/film.sh render shaders
INCOMING_TRAILER_DIR=film-war2 INCOMING_FILM_OUT=render-ends INCOMING_FILM_TRIES=2 INCOMING_FILM_SHOTS=p48,p50 \
  tools/laptop_job.sh film-render -- tools/trailer/film.sh render shaders
for R in film-war6/render film-war4/render-metro2 film-war2/render-ends; do
  python3 tools/trailer/report.py mod/run/scenario/$R --md mod/run/scenario/$R/report.md
done
```
В логе записи: нет `момент не наступил` у этих планов и нет `the launcher is busy with the previous order`; прислать
строки `TRAILER p10`, `TRAILER p20`, `TRAILER p01c`, `TRAILER p35`, `SCENARIO film`. Из вывода — три `report.md` и
`best.txt`, строки `SCENARIO film heap`/`restart`, ошибки (как в п. 3); у p01c и p20 в `report.md` мотор больше 0 %.
Потом — п. 6.
