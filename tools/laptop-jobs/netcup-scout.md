# Задание netcup: разведка мест камер трейлера на Newisle и черновые кадры (только процессор, без игры)

Ветка `claude/project-thread-1bcgqw` (zentixua/incoming), коммит — в сообщении координатора. Зачем: сценарий трейлера
на Newisle (Север — Город 1, Юг — Город 2) расставлен по карте замыслами камер: 37 планов в
`tools/trailer/scenes/newisle.json`. `tools/trailer/scout.py` по высотам изучения карты находит место каждой камеры
(крыша у края, земля, вершина, улица, путь), проверяет луч к цели и рисует черновой кадр лучами по блокам копии для
съёмки — из них аниматик для Артёма. Сначала изучение карты ещё раз той же строкой, что третий проход: теперь оно
пишет и `water.npy` (вода сверху), чтобы камера на земле не вставала на озеро. Только чтение регионов; игровой сервер
не нужен и не запускается, сервер «Newisle View» не трогать: задачи идут с `nice -n 19`, памяти — до ~3 ГБ на пике
(рамка кадра до 1000 × 1000 блоков), свободными остаются не меньше 4 ГБ.

```sh
cd <рабочее дерево incoming> && git fetch origin claude/project-thread-1bcgqw
git worktree remove --force ~/claude/map-wt 2>/dev/null; git worktree add ~/claude/map-wt <коммит>
[ -x ~/claude/shotenv/bin/python ] || python3 -m venv ~/claude/shotenv
~/claude/shotenv/bin/pip install -q numpy pillow scipy
cd ~/claude/map-wt && ~/claude/shotenv/bin/python -m unittest discover -s tools/trailer/tests   # OK
R=$(dirname "$(find ~/airstrike-work/newisle-film-1014 -path '*/region/r.0.0.mca' -not -path '*/DIM*' | head -1)"); echo "$R"
nice -n 19 ~/claude/shotenv/bin/python tools/trailer/mapstudy.py "$R" ~/claude/mapstudy \
  --box -2496 -1552 1056 1472 --map -2240 -1296 800 1216 --jobs 6 \
  --points "Город 1:-175,-113;Город 2:-770,680;Ферма:-1116,316;Пустынный городок:-561,-1024;Аэропорт:308,-680;Автострада:51,-445;Горы с видом:-660,-266;Ущелье:-1004,-79;Крыша Города 1:-167,-4;Крыша Города 2:-748,660;Вершина:-584,-353;Мост через озеро:-529,250;Стадион:-660,480" \
  --crops "Город 1:-175,-113,320;Город 2:-739,660,260;Аэропорт:308,-700,220;Автострада и порт:20,-470,200;Горы с видом:-640,-300,220;Ущелье:-1004,-79,200;Ферма:-1116,316,160;Пустынный городок:-561,-1024,200;Озеро между городами:-500,300,320" \
  2>&1 | tail -3
ls ~/claude/mapstudy/water.npy
nice -n 19 ~/claude/shotenv/bin/python tools/trailer/scout.py tools/trailer/scenes/newisle.json ~/claude/mapstudy \
  ~/claude/scout --regions "$R" --previs 2>&1 | tee ~/claude/scout.log
```

Прислать (в корень файлов проекта, с приставкой `newisle-scout-`): `scout.md`, `poses.json`, все `sheet-*.jpg` (4
листа), все кадры `n*.jpg` (около 55, 768 × 432) и последние 10 строк `scout.log`. Это текст и черновые картинки
лучами (цвета блоков по карте) — как картинки сверху у изучения карты; файлов карты в них нет.

После: `git worktree remove ~/claude/map-wt`. Папки `~/claude/mapstudy` и `~/claude/scout` оставить до конца подготовки
трейлера.
