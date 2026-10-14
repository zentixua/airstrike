# Задание netcup: разведка мест камер трейлера на Newisle и черновые кадры, четвёртый проход (только процессор, без игры)

Ветка `claude/project-thread-1bcgqw` (zentixua/incoming), коммит — в сообщении координатора. Зачем: сценарий трейлера
на Newisle (Север — Город 1, Юг — Город 2) расставлен по карте замыслами камер в `tools/trailer/scenes/newisle.json`;
`tools/trailer/scout.py` по высотам изучения карты находит место каждой камеры и рисует черновой кадр лучами по
блокам копии для съёмки — из них аниматик для Артёма. Третий проход (14.10, 08:58) дал кадры всем 41 плану.
Четвёртый — 7 планов: конец света (второй ключ, МБР, MISSILE ATTACK, вспышка, волна) перенесён на ночь, а их кадры
второго прохода — дневные; у блэкаута камера отходит от башни: в кадре была её стена. Изучение карты не повторяется:
`~/claude/mapstudy` второго прохода (с `canopy.npy`) остаётся как есть. Только чтение регионов; игровой сервер не нужен и не запускается, сервер «Newisle View» не трогать: задачи идут
с `nice -n 19`, памяти — до ~3 ГБ на пике, свободными остаются не меньше 4 ГБ (перед запуском — `free -g`: доступно меньше
8 ГБ — не запускать, прислать вывод `free -g`).

```sh
cd <рабочее дерево incoming> && git fetch origin claude/project-thread-1bcgqw
git worktree remove --force ~/claude/map-wt 2>/dev/null; git worktree add ~/claude/map-wt <коммит>
cd ~/claude/map-wt && ~/claude/shotenv/bin/python -m unittest discover -s tools/trailer/tests   # OK
R=$(dirname "$(find ~/airstrike-work/newisle-film-1014 -path '*/region/r.0.0.mca' -not -path '*/DIM*' | head -1)"); echo "$R"
ls ~/claude/mapstudy/water.npy ~/claude/mapstudy/canopy.npy
free -g
nice -n 19 ~/claude/shotenv/bin/python tools/trailer/scout.py tools/trailer/scenes/newisle.json ~/claude/mapstudy \
  ~/claude/scout4 --regions "$R" --previs --only n27,n30,n31,n32,n33,n34,n36 2>&1 | tee ~/claude/scout4.log
```

Прислать (в корень файлов проекта, с приставкой `newisle-scout4-`): `scout.md`, `poses.json`, все `sheet-*.jpg`, все
кадры `n*.jpg` (7, 768 × 432) и последние 10 строк `scout4.log`. Это текст и черновые картинки лучами (цвета
блоков по карте) — как картинки сверху у изучения карты; файлов карты в них нет.

После: `git worktree remove ~/claude/map-wt`. Папки `~/claude/mapstudy`, `~/claude/scout`, `~/claude/scout2`,
`~/claude/scout3` и `~/claude/scout4` оставить до конца подготовки трейлера.
