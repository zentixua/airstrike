# Задание ноутбуку: камеры по сцене для планов трейлера без камеры и с FAIL

Ветка `claude/project-thread-aealhl` (zentixua/incoming) — ветка трейлера на `345965c` плюс `tools/shot` и кандидат
`<тело>-planned` в `ReplayFilm`; коммит — в сообщении координатора. Только после конца прохода трейлера
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

## Шаг 2: планы и черновой показ (процессор)
```sh
P2=""; P4=""
for S in p01d p18 p25 p28 p37 p39c p01e p12 p19b p23b p29 p32 p38 p39b p40b p41; do
  if [ -f $W4/replays/$S.events.jsonl ]; then P4="$P4 $W4/replays/$S.events.jsonl"
  elif [ -f $W2/replays/$S.events.jsonl ]; then P2="$P2 $W2/replays/$S.events.jsonl"; else echo "нет журнала $S"; fi
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

## Шаг 3: вывод спланированных камер (видеокарта)
В рабочем дереве съёмки (там, где идёт `film.sh`), когда проход закончен: `git checkout <коммит>` (ветка трейлера и
кандидат `-planned`; без файлов `planned/` вывод тот же, что на `345965c`), в конце — обратно `git checkout 345965c`.
Планы — те, где у лучшей камеры в «Итоге» лога PASS (мера ждёт, что проверка кадров плана пройдёт):
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
Прислать: `render-planned/report.md` и `best.txt` обеих папок, строки `SCENARIO film solve <план>` и
`SCENARIO film verdict`, по кадру из середины каждого вывода.

## После
`git worktree remove mod/run/shot-wt`; `mod/run/shot-out` оставить до конца работы над трейлером (кэш карты).
