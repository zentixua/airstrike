# Задание ноутбуку: камеры по сцене для планов трейлера без камеры и с FAIL

Ветка `claude/project-thread-aealhl` (zentixua/incoming) — ветка трейлера (с `8d25ef91`: камера «в блоках» ближе 0,2
блока) плюс `tools/shot` и кандидат `<тело>-planned` в `ReplayFilm`; коммит — в сообщении координатора. Только после конца прохода трейлера
(`trailer-film.md`) и не во время игры Артёма. Удалять ничего нельзя без его слова; Zearth не выносить — наружу только
текст, `sheet.jpg` и `previs.mp4`.

Цель: (1) проверить меру `tools/shot` на настоящих выводах — камера каждого кадра из `qa.jsonl`, та же мера, что у
планировщика, против проверки кадров; (2) спланировать камеры для 16 планов, у которых 13.10 не было камеры (p01d p18
p25 p28 p37 p39c) или вывод не прошёл (p01e p12 p19b p23b p29 p32 p38 p39b p40b p41), и посмотреть черновой показ;
(3) вывести спланированные камеры на видеокарте. Шаги 1–2 — процессор, минуты; шаг 3 — видеокарта.

```sh
cd /mnt/data/projects/airstrike && git fetch origin claude/project-thread-aealhl
git worktree add mod/run/shot-wt <коммит>
W2=$PWD/mod/run/scenario/film-war2 W4=$PWD/mod/run/scenario/film-war4 OUT=$PWD/mod/run/shot-out
R="$PWD/mod/run/zearth-master/Project Zearth/world/region"   # карта мастера
cd mod/run/shot-wt && uv run --with numpy --with scipy --with pillow python -m unittest discover -s tools/shot/tests
```

## Шаг 1: мера против вывода (процессор)
```sh
uv run tools/shot/shot.py check $W2/render-human/*/*/qa.jsonl $W2/render/*/*/qa.jsonl --regions "$R" \
    --out $OUT/check-war2 --shown 1.5 --clouds 320 2>&1 | tee $OUT/check-war2.log
uv run tools/shot/shot.py check $W4/render/*/*/qa.jsonl --regions "$R" --out $OUT/check-war4 --clouds 320 \
    2>&1 | tee $OUT/check-war4.log
```

В конце `check-war4.log` — строка «Журнал сбит»: планы film-war4, где снаряд в выводе дальше 8 блоков от места журнала
(журнал записан клиентом 52d661a с изъяном очереди мест: при отстающем сервере снаряд у клиента шёл по прямой прошлым
шагом, а вывод на 345965c показывает его путь). Камера по такому журналу смотрит не туда.

## Шаг 2: планы и черновой показ (процессор)
Каждый план — по обеим записям, где журнал есть: какую выводить, решает шаг 3.
```sh
P2=""; P4=""
for S in p01d p18 p25 p28 p37 p39c p01e p12 p19b p23b p29 p32 p38 p39b p40b p41; do
  [ -f $W4/replays/$S.events.jsonl ] && P4="$P4 $W4/replays/$S.events.jsonl"
  [ -f $W2/replays/$S.events.jsonl ] && P2="$P2 $W2/replays/$S.events.jsonl"
  [ -f $W4/replays/$S.events.jsonl ] || [ -f $W2/replays/$S.events.jsonl ] || echo "нет журнала $S"
done
uv run tools/shot/shot.py plan $P2 --regions "$R" --out $OUT/war2 --shown 1.5 --clouds 320 --previs all --every 3 \
    --size 640x360 2>&1 | tee $OUT/war2.log
uv run tools/shot/shot.py plan $P4 --regions "$R" --out $OUT/war4 --clouds 320 --previs all --every 3 \
    --size 640x360 2>&1 | tee $OUT/war4.log
```

Прислать (SendUserFile кладёт в корень `/mnt/project-files`, поэтому имена — с приставкой `filming-rethink-laptop-1-`):
`check-war2.log`, `check-war4.log`, `war2.log`, `war4.log`; для каждого плана `summary.txt` (→ `<план>-summary.txt`),
`sheet.jpg` каждой камеры (→ `<план>-<камера>.jpg`) и `previs.mp4` лучшей (→ `<план>-<камера>.mp4`). `world.npz`
(карта) — не выносить.

## Шаг 2б: планы заново с новым выбором (процессор, после шагов 1–2 на прежнем коммите)
Объект плана теперь выбирается как у трейлера (`Rig`): по всему окну, а не по первым `span` тикам, из равных — первый
по появлению, у film-war2 — по сглаженным местам. Камера — первое тело по замыслу, если оно показывает объект почти как
лучшее (p12: FPV в кронах с объектом в 42 % кадров против дрона с 90 %). Три плана, где `check` показал, что вывод
ведёт другой снаряд, — прямо по номеру пути. Карта из кэша берётся, если накрывает новую рамку. `W2`, `W4`, `OUT`, `R`,
`P2`, `P4` — как в шагах 1–2.
```sh
cd /mnt/data/projects/airstrike/mod/run/shot-wt && git fetch origin claude/project-thread-aealhl && git checkout <коммит>
uv run --with numpy --with scipy --with pillow python -m unittest discover -s tools/shot/tests
uv run tools/shot/shot.py plan $P2 --regions "$R" --out $OUT/war2 --shown 1.5 --clouds 320 --previs best --every 3 \
    --size 640x360 --subject p32=track:3,p01e=track:2 2>&1 | tee $OUT/war2-2.log
uv run tools/shot/shot.py plan $P4 --regions "$R" --out $OUT/war4 --clouds 320 --previs best --every 3 \
    --size 640x360 --subject p12=track:1 2>&1 | tee $OUT/war4-2.log
```
Прислать с приставкой `filming-rethink-laptop-2-`: `war2-2.log`, `war4-2.log`, `<план>-summary.txt` и `previs.mp4`
лучшей камеры, где она сменилась. Шаг 3 берёт `planned/` отсюда (те же папки).

## Шаг 3: вывод спланированных камер (видеокарта)
В рабочем дереве съёмки (там, где идёт `film.sh`), когда проход закончен: `git checkout <коммит>` (ветка трейлера с
`8d25ef91` и кандидат `-planned`; без файлов `planned/` вывод тот же, что на ветке трейлера), в конце — обратно на коммит, где дерево съёмки стояло.
Планы — те, где у лучшей камеры в «Итоге» лога шага 2б (`war2-2.log`, `war4-2.log`) PASS (мера ждёт, что проверка кадров плана пройдёт): из film-war4,
если там PASS и плана нет в строке «Журнал сбит» `check-war4.log` (и у плана в film-war4 был удар — p38 и p40b его
не дождались), иначе из film-war2, если там PASS:
```sh
cp -r $OUT/war2/planned $W2/; cp -r $OUT/war4/planned $W4/    # какие есть
INCOMING_TRAILER_DIR=film-war2 INCOMING_FILM_SHOWN=1 INCOMING_FILM_OUT=render-planned INCOMING_FILM_TRIES=2 \
INCOMING_FILM_CAMERA='.*-planned(-raw)?' INCOMING_FILM_SHOTS=<планы war2 через запятую> \
  tools/laptop_job.sh film-render -- tools/trailer/film.sh render shaders
INCOMING_TRAILER_DIR=film-war4 INCOMING_FILM_OUT=render-planned INCOMING_FILM_TRIES=2 \
INCOMING_FILM_CAMERA='.*-planned(-raw)?' INCOMING_FILM_SHOTS=<планы war4 через запятую> \
  tools/laptop_job.sh film-render -- tools/trailer/film.sh render shaders
python3 tools/trailer/report.py $W2/render-planned --md $W2/render-planned/report.md   # и для $W4
```
Вывод с этого коммита пишет и свой журнал (`<кандидат>/<план>.events.jsonl` рядом с `qa.jsonl`) — сверка меры по нему:
```sh
uv run tools/shot/shot.py check $W2/render-planned/*/*/qa.jsonl $W4/render-planned/*/*/qa.jsonl --regions "$R" \
    --out $OUT/check-planned --clouds 320 2>&1 | tee $OUT/check-planned.log
```
Прислать: `render-planned/report.md` и `best.txt` обеих папок, `check-planned.log`, строки `SCENARIO film solve <план>` и
`SCENARIO film verdict`, по кадру из середины каждого вывода.

## Шаг 4: сверка вывода заново (процессор, минуты; можно рядом с работой видеокарты)
`check` теперь выбирает объект по журналу записи (как `Rig`), а места берёт из журнала вывода по UUID снаряда: по камере
плана в журнале вывода правило «дольше всех в кадре» брало другой снаряд (p01d, p25, p28, p38 — «другой объект» при
PASS вывода). У p32 он ещё скажет, сдвинулся ли удар между журналами («УДАР У ВЫВОДА ДРУГОЙ»).
```sh
cd /mnt/data/projects/airstrike/mod/run/shot-wt && git fetch origin claude/project-thread-aealhl && git checkout <коммит>
uv run --with numpy --with scipy --with pillow python -m unittest discover -s tools/shot/tests
uv run tools/shot/shot.py check $W2/render-planned/*/*/qa.jsonl --regions "$R" --out $OUT/check-planned \
    --shown 1.5 --clouds 320 2>&1 | tee $OUT/check-planned-war2-2.log
uv run tools/shot/shot.py check $W4/render-planned/*/*/qa.jsonl --regions "$R" --out $OUT/check-planned \
    --clouds 320 2>&1 | tee $OUT/check-planned-war4-2.log
```
Прислать `check-planned-war2-2.log` и `check-planned-war4-2.log` с приставкой `filming-rethink-laptop-4-`.

## Шаг 5: p32 заново (процессор — минута, видеокарта — один план)
Мера удара смотрела в точку на 0,35 шара над местом удара, а проверка кадров — в шар у самой земли: у p32 мера видела
удар поверх низкой преграды (1,00), вывод — нет (0 %, за блоками). Теперь мера берёт ту же точку и запас, что `FrameQa`.
`check` у камеры по сцене ещё сверяет камеру вывода с камерой плана («КАМЕРА НЕ ПО ПЛАНУ»).
```sh
cd /mnt/data/projects/airstrike/mod/run/shot-wt && git fetch origin claude/project-thread-aealhl && git checkout <коммит>
uv run --with numpy --with scipy --with pillow python -m unittest discover -s tools/shot/tests
uv run tools/shot/shot.py plan $W2/replays/p32.events.jsonl --regions "$R" --out $OUT/war2 --shown 1.5 --clouds 320 \
    --previs best --every 3 --size 640x360 --subject p32=track:3 2>&1 | tee $OUT/war2-p32-3.log
cp $OUT/war2/planned/p32.camera.jsonl $W2/planned/p32.camera.jsonl
```
Если у лучшей камеры в «Итоге» лога PASS — вывод на видеокарте, когда она свободна (в дереве съёмки, как в шаге 3, на
этом коммите; `render-planned2` — чтобы не трогать прежний вывод):
```sh
INCOMING_TRAILER_DIR=film-war2 INCOMING_FILM_SHOWN=1 INCOMING_FILM_OUT=render-planned2 INCOMING_FILM_TRIES=2 \
INCOMING_FILM_CAMERA='.*-planned(-raw)?' INCOMING_FILM_SHOTS=p32 \
  tools/laptop_job.sh film-render -- tools/trailer/film.sh render shaders
python3 tools/trailer/report.py $W2/render-planned2 --md $W2/render-planned2/report.md
uv run tools/shot/shot.py check $W2/render-planned2/*/*/qa.jsonl $W2/render-planned/p32/*/qa.jsonl --regions "$R" \
    --out $OUT/check-planned --shown 1.5 --clouds 320 2>&1 | tee $OUT/check-p32-3.log
```
Прислать с приставкой `filming-rethink-laptop-5-`: `war2-p32-3.log`, `previs.mp4` лучшей камеры p32, а после вывода —
`report.md`, `best.txt`, `check-p32-3.log` и кадр из середины.

## После
`git worktree remove mod/run/shot-wt`; `mod/run/shot-out` оставить до конца работы над трейлером (кэш карты).
