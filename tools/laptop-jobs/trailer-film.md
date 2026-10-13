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
Сразу после записи, с настройками пака по умолчанию, все планы `film-war4`:
```sh
INCOMING_TRAILER_DIR=film-war4 INCOMING_FILM_TRIES=2 tools/laptop_job.sh film-render -- tools/trailer/film.sh render shaders
python3 tools/trailer/report.py mod/run/scenario/film-war4/render --md mod/run/scenario/film-war4/render/report.md
```
В логе — то же, что в прошлом выводе: `dh generator DISABLED PRE_EXISTING_ONLY`; нет `Failed to handle packet`,
`DecoderException`, `повтор закрылся` (есть — стек целиком с 30 строками до него); строки `SCENARIO film heap` и
`SCENARIO film restart` — прислать; `"dh":1` в `qa.jsonl` выводимых кандидатов.

Журналы — по каждому плану, где итог вывода (п. 3) не PASS, текстом одним файлом:
```sh
W4=mod/run/scenario/film-war4
for S in $(awk '$3 != "PASS" {print $1}' $W4/render/best.txt); do
  echo "== $S"; uv run tools/trailer/journal.py $W4/replays/$S.events.jsonl --region mod/run/scenario/saves/war4-retake/region
done > $W4/journal.txt 2>&1
```

## 4. Монтаж
Поверх всего `film-war2` (прежний вывод — запасной для плана, который теперь не прошёл): p03 с туманом c2 (новый
вывод, если есть), `render-human`, затем живые планы и вывод `film-war4`. Строки `best.txt` с «-» вместо папки — план
не снят (причина в той же строке), в `--rec` их нет.
```sh
W2=mod/run/scenario/film-war2 W4=mod/run/scenario/film-war4
rec() { [ -f "$1" ] && awk -v s="${2:-}" '$2 != "-" && (s == "" || $1 == s) {print "--rec " $2}' "$1"; }
uv run tools/trailer/edit.py --lang en --rec $W2 $(rec $W2/render/best.txt) \
  $(rec $W2/render-fog-c2-alt110-d3.05-dist200/best.txt p03) $(rec $W2/render-fog-c2-human/best.txt p03) \
  $(rec $W2/render-human/best.txt) --rec $W4 $(rec $W4/render/best.txt) --music <Juggernaut.mp3>
```
Звук пуска p01c (монтаж прежде писал «p01c: no sound:rocket.launch»): какие звуки есть в журнале выведенного p01c —
```sh
grep -o '"event":"[^"]*"' "$(awk '$1 == "p01c" {print $2}' $W4/render/best.txt)/timeline.jsonl" | sort | uniq -c | sort -rn | head -20
```

Прислать: `dist/incoming-trailer-lite.mp4` (до 20 МиБ), `film-war4/render/report.md`, `film-war4/render/best.txt`,
`film-war4/journal.txt`, весь вывод `edit.py` (заглушки и предупреждения), счёт звуков p01c, строки из п. 2 и п. 3,
ошибки из логов записи и вывода (`Exception`, `ERROR` мода).
