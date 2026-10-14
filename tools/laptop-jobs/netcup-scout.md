# Задание netcup: разведка мест камер трейлера на Newisle и черновые кадры, третий проход (только процессор, без игры)

Ветка `claude/project-thread-1bcgqw` (zentixua/incoming), коммит — в сообщении координатора. Зачем: сценарий трейлера
на Newisle (Север — Город 1, Юг — Город 2) расставлен по карте замыслами камер в `tools/trailer/scenes/newisle.json`;
`tools/trailer/scout.py` по высотам изучения карты находит место каждой камеры и рисует черновой кадр лучами по
блокам копии для съёмки — из них аниматик для Артёма. Второй проход (14.10, 08:36) нашёл места 35 планов из 38.
Третий — только 15 планов: новые (наводчик на рассвете, FPV войны на закате и ночью), переставленные после второго
прохода (первый удар, ЗРК, дирижабль, «Град», блэкаут, пик, вход в метро) и пути FPV под настоящую скорость полёта;
у волны с крыши Города 2 рамка кадра шире (до 1,6 млн блоков площади, ~5 ГБ на пике), иначе Город 1 за 875 блоков
в кадр не попадал. Изучение карты не повторяется: `~/claude/mapstudy` второго прохода (с `canopy.npy`) остаётся как
есть. Только чтение регионов; игровой сервер не нужен и не запускается, сервер «Newisle View» не трогать: задачи идут
с `nice -n 19`, памяти — до ~5 ГБ на пике, свободными остаются не меньше 4 ГБ (перед запуском — `free -g`: доступно меньше
10 ГБ — не запускать, прислать вывод `free -g`).

```sh
cd <рабочее дерево incoming> && git fetch origin claude/project-thread-1bcgqw
git worktree remove --force ~/claude/map-wt 2>/dev/null; git worktree add ~/claude/map-wt <коммит>
cd ~/claude/map-wt && ~/claude/shotenv/bin/python -m unittest discover -s tools/trailer/tests   # OK
R=$(dirname "$(find ~/airstrike-work/newisle-film-1014 -path '*/region/r.0.0.mca' -not -path '*/DIM*' | head -1)"); echo "$R"
ls ~/claude/mapstudy/water.npy ~/claude/mapstudy/canopy.npy
free -g
nice -n 19 ~/claude/shotenv/bin/python tools/trailer/scout.py tools/trailer/scenes/newisle.json ~/claude/mapstudy \
  ~/claude/scout3 --regions "$R" --previs \
  --only n02,n04,n07a,n14,n16,n17,n18,n20,n21,n25a,n26a,n27,n29a,n29c,n35 2>&1 | tee ~/claude/scout3.log
```

Прислать (в корень файлов проекта, с приставкой `newisle-scout3-`): `scout.md`, `poses.json`, все `sheet-*.jpg`, все
кадры `n*.jpg` (около 30, 768 × 432) и последние 10 строк `scout3.log`. Это текст и черновые картинки лучами (цвета
блоков по карте) — как картинки сверху у изучения карты; файлов карты в них нет.

После: `git worktree remove ~/claude/map-wt`. Папки `~/claude/mapstudy`, `~/claude/scout`, `~/claude/scout2` и
`~/claude/scout3` оставить до конца подготовки трейлера.
