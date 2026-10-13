# Задание ноутбуку: война 06.10 — превью TikTok по моментам Артёма, пересъёмка трёх планов

Коммит zentixua/incoming — в сообщении координатора. Повтор — копия войны `2026-10-09T15_24_55` (Incoming 2.10.3);
моменты назвал Артём по шкале Flashback. На видеокарте — одна тяжёлая задача (между частями трейлера, не рядом с
ними), не во время игры Артёма (`pgrep -f 'instances/Airstrike Pack'` пусто). Инстанс, его миры и настройки не трогать.
Пути — от `/mnt/data/projects/airstrike`, `JAVA_HOME` — `java-runtime-delta` из Prism,
`W=/mnt/data/projects/airstrike/mod/run/claude-work`, `T=$W/tiktok/war`, `R=2026-10-09T15_24_55`.

Первая съёмка (`$T/clips`, коммит e64942f) — не трогать: сбитие 23:57 из неё уже отдано. Переснимаются три плана, в
`$T/clips2`:
- 53:00–53:26 — глазами Артёма в метро (подрыв 53:08): спирали его зелий у камеры закрывали пол-кадра, теперь клиент их
  у камеры не рождает;
- тот же подрыв свободной камерой снаружи (кандидат «5. Ядерный удар»): объектив 19° давал во весь кадр огонь, теперь
  угол — по мощности подрыва из пакета, шар с подъёмом в кадре;
- 25:02–25:40 — пролёт мимо порта: низ кадра был над морем вне чанков повтора (дно и пещеры без воды), теперь путь
  медленнее и сдвинут так, чтобы глаз и низ кадра были в чанках записавшего.

## 1. Сборка
```sh
git -C $W/tiktok-war-wt fetch origin <коммит> && git -C $W/tiktok-war-wt checkout --detach <коммит>; S=$W/tiktok-war-wt/tools
$W/tiktok-war-wt/mod/gradlew -p $S/replayscan build
G=$W/tiktok/game/2.10.3; J=$(ls $G/day/*.jar); echo "$J"            # клиент первой съёмки; jar мода — один
rm $G/mods/replayscan-*.jar && cp $S/replayscan/build/libs/replayscan-1.0.0.jar $G/mods/
```

## 2. Моменты и план
```sh
mkdir -p $T
uv run $S/clips/moments.py $W/replayscan/out/$R/replay.duckdb --out $T/clips2 --pov-top 50 --at 53:00/53:26 \
  --fly 'at=25:02/25:40 from=-102,121,-628 to=-327,121,-572 to_at=25:32 focus=25:16/25:22 look=south turn=30' | tee $T/moments2.txt
NUKE=$(python3 -c "import json; print(','.join(c['id'] for c in json.load(open('$T/clips2/candidates.json'))['nuke'] if 63600 <= c['t'] <= 64000))")
echo "$NUKE"                                                        # один id: подрыв 53:08
uv run $S/clips/plan.py $T/clips2/candidates.json --all pov,fly --pick "$NUKE" --replays $W/replayscan/replays
```
В `moments2.txt`: строка ядерного подрыва (радиус шара и угол объектива), у пролёта — «уходил из чанков записавшего …:
скорость ×…, сдвиг (…)», «глаз и низ кадра — не дальше … блоков от него», удары 25:16–25:22 «в кадре». Строки «ни
медленнее, ни сдвигом … не встаёт» или «ни одного удара в кадре» — пролёт не снимать (`plan.py … --pick "$NUKE" --all pov`),
в отчёт — `moments2.txt`.

## 3. Съёмка (видеокарта)
```sh
$S/laptop_job.sh tiktok-war2 -- python3 $S/prod_client.py --no-copy --dir $G --incoming-jar $J --jvm=-Xmx12g \
  --prop replayscan.film=$T/clips2/film/$R/plan.json
```
Успех: в `$G/logs/latest.log` — `Film: done`; у каждого плана — `Film: verdict <план> <камера>: PASS` (в `verdict.json` —
`dh_world` 0, `low_out` 0); у отрезка глазами — `spectates ZentixUA`; строки `effect particles stay` нет. Не прошедший
план не доводить: в отчёт его `why`.

## 4. Монтаж и выдача
```sh
uv run $S/clips/edit.py $T/clips2/film --out $T/clips2/out --soon
```
Ролики `$T/clips2/out/{pov-0,fly-0,<NUKE>}.mp4`, подписи `.txt` и `report.md` — в файлы проекта, `tiktok-war/v2/`
(до 20 МиБ; больше — H.264 CRF 20, звук как есть). Прислать: `report.md`, `moments2.txt`, строки `Film: verdict` и
`spectates`, ошибки (`Exception`, `ERROR`) лога съёмки.
