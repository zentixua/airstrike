# Задание netcup: изучение карты Newisle по регионам, второй проход (только процессор, без игры)

Ветка `claude/project-thread-1bcgqw` (zentixua/incoming), коммит — в сообщении координатора. Зачем: Артём просит
понимать географию и устройство карты (районы, высотки, улицы, дороги, вода, мосты, метро, аэропорт, горы и виды) —
по ним ставятся сцены трейлера. `tools/trailer/mapstudy.py` читает файлы регионов копии для съёмки и пишет сводку и
картинки сверху. Второй проход: первый (14.10, 07:17) принял шахты генерации и пласты камня Create за метро и
тоннели, не нашёл полосы аэропорта (около 180 × 15) и подписал точки квадратами (шрифт без кириллицы). Только чтение
регионов; игровой сервер не нужен и не запускается, сервер «Newisle View» не трогать: задача идёт с `nice -n 19`,
6 процессов, памяти — до ~3 ГБ (свободными остаются не меньше 4 ГБ).

```sh
cd <рабочее дерево incoming> && git fetch origin claude/project-thread-1bcgqw
git worktree remove --force ~/claude/map-wt 2>/dev/null; git worktree add ~/claude/map-wt <коммит>
fc-list 2>/dev/null | grep -c -i -E "dejavu|noto|liberation|freesans"   # 0 — подписи будут номерами, имена — в сводке
[ -x ~/claude/shotenv/bin/python ] || { python3 -m venv ~/claude/shotenv && ~/claude/shotenv/bin/pip install -q numpy pillow; }
cd ~/claude/map-wt && ~/claude/shotenv/bin/python -m unittest discover -s tools/trailer/tests   # OK
R=$(dirname "$(find ~/airstrike-work/newisle-film-1014 -path '*/region/r.0.0.mca' -not -path '*/DIM*' | head -1)"); echo "$R"
nice -n 19 ~/claude/shotenv/bin/python tools/trailer/mapstudy.py "$R" ~/claude/mapstudy \
  --box -2496 -1552 1056 1472 --map -2240 -1296 800 1216 --jobs 6 \
  --points "Город 1:-175,-113;Город 2:-770,680;Ферма:-1116,316;Пустынный городок:-561,-1024;Аэропорт:308,-680;Автострада:51,-445;Горы с видом:-660,-266;Ущелье:-1004,-79" \
  --crops "Город 1:-175,-113,320;Город 2:-739,660,260;Аэропорт:308,-700,220;Автострада и порт:20,-470,200;Горы с видом:-640,-300,220;Ущелье:-1004,-79,200;Ферма:-1116,316,160;Пустынный городок:-561,-1024,200;Озеро между городами:-500,300,320" \
  2>&1 | tee ~/claude/mapstudy.log
```

Рамка `--box` — застройка карты (x −2240…799, z −1296…1215, задание B) и 256 блоков вокруг; `--map` — сама застройка,
до её края считается «до края» у точек.

Прислать (в корень файлов проекта, с приставкой `newisle-mapstudy-v2-`): `mapstudy.md`, `mapstudy.json`, `map.png`,
`heights.png`, `infra.png`, все `crop-*.jpg` (18 штук) и последние 10 строк `mapstudy.log` (время и ошибки, если
были). Это текст и картинки сверху (2 блока на пиксель, вырезки — 1) — как картинка края у задания B; файлов карты
в них нет.

После: `git worktree remove ~/claude/map-wt`. Папку `~/claude/mapstudy` (`top.npy`, `ground.npy` — высоты для
разведки мест) оставить до конца подготовки трейлера.
