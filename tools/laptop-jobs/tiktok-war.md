# Задание ноутбуку: война 06.10 — превью TikTok, пролёт у порта (и ядерка с метро, если ещё не сняты)

Коммит zentixua/incoming — в сообщении координатора. Повтор — копия войны `2026-10-09T15_24_55` (Incoming 2.10.3);
моменты назвал Артём по шкале Flashback. На видеокарте — одна тяжёлая задача (между частями трейлера, не рядом с
ними), не во время игры Артёма (`pgrep -f 'instances/Airstrike Pack'` пусто). Инстанс, его миры и настройки не трогать.
Пути — от `/mnt/data/projects/airstrike`, `JAVA_HOME` — `java-runtime-delta` из Prism,
`W=/mnt/data/projects/airstrike/mod/run/claude-work`, `T=$W/tiktok/war`, `R=2026-10-09T15_24_55`.

Первая съёмка (`$T/clips`) и `$T/clips2` (задание tiktok-war2, коммит 194f522) — не трогать. Снимается в `$T/clips3`.
Пролёт 25:02–25:40 шёл над пустотой (дно и пещеры DH под камерой): Flashback держит в мире повтора все чанки, которые
запись уже прислала, но только если повтор шёл с начала, а съёмка прыгала сразу к плану — в мире были только чанки
вокруг Артёма в ту минуту. Теперь съёмка прогоняет повтор с начала до каждого плана (планы — по времени), а пролёт
проверяется по чанкам, которые запись прислала до него (прорисовка Артёма — по обновлениям блоков).

## 1. Сборка
```sh
git -C $W/tiktok-war-wt fetch origin <коммит> && git -C $W/tiktok-war-wt checkout --detach <коммит>; S=$W/tiktok-war-wt/tools
$W/tiktok-war-wt/mod/gradlew -p $S/replayscan build
G=$W/tiktok/game/2.10.3; J=$(ls $G/day/*.jar); echo "$J"            # клиент первой съёмки; jar мода — один
rm $G/mods/replayscan-*.jar && cp $S/replayscan/build/libs/replayscan-1.0.0.jar $G/mods/
```

## 2. Моменты и план
```sh
uv run $S/clips/moments.py $W/replayscan/out/$R/replay.duckdb --out $T/clips3 --pov-top 50 --at 53:00/53:26 \
  --fly 'at=25:02/25:40 from=-102,121,-628 to=-327,121,-572 to_at=25:32 focus=25:16/25:22 look=south turn=30' | tee $T/moments3.txt
NUKE=$(python3 -c "import json; print(','.join(c['id'] for c in json.load(open('$T/clips3/candidates.json'))['nuke'] if 63600 <= c['t'] <= 64000))")
echo "$NUKE"                                                        # один id: подрыв 53:08
python3 -c "import json; b=json.load(open('$T/clips2/film/$R/film/best.json')); print({k: v.get('pass') for k, v in b.items()})"
```
Если в `$T/clips2/film/$R/film/best.json` ядерка снаружи и отрезок в метро прошли (`pass` true у обоих) — снимается
только пролёт: `uv run $S/clips/plan.py $T/clips3/candidates.json --all fly --replays $W/replayscan/replays`. Иначе (файла
нет, не прошли, задание tiktok-war2 не запускалось) — все три:
`uv run $S/clips/plan.py $T/clips3/candidates.json --all pov,fly --pick "$NUKE" --replays $W/replayscan/replays`.

В `moments3.txt` у пролёта: «прорисовка записавшего N чанков (по … обновлениям блоков); чанки у from — с …, у to —
с …», «записавший ближе всего к from/to: …», «удары по земле … за всю запись: …», «в чанках повтора — K из K проверок»,
удары 25:16–25:22 «в кадре». Строка «то же место в …» — пролёт в другое время записи, где место в чанках (так и
снимать). Строка «ни медленнее, ни сдвигом, ни в другое время … не встаёт» — пролёт не снимать (из плана — `--all pov`
или ничего), в отчёт — `moments3.txt`.

## 3. Съёмка (видеокарта)
```sh
$S/laptop_job.sh tiktok-war3 -- python3 $S/prod_client.py --no-copy --dir $G --incoming-jar $J --jvm=-Xmx12g \
  --prop replayscan.film=$T/clips3/film/$R/plan.json
```
Успех: в `$G/logs/latest.log` — `Film: done`; перед каждым планом — `Film: <план>: replay played from the start to tick …`
(строки `replay stopped at tick … on the way` нет); у каждого плана — `Film: verdict <план> <камера>: PASS`
(в `verdict.json` — `dh_world` 0, `low_out` — до 2 % кадров); у отрезка глазами — `spectates ZentixUA`; строки
`effect particles stay` нет. Не прошедший план не доводить: в отчёт его `why`.

## 4. Монтаж и выдача
```sh
uv run $S/clips/edit.py $T/clips3/film --out $T/clips3/out --soon
```
Ролики `$T/clips3/out/*.mp4`, подписи `.txt` и `report.md` — в файлы проекта (до 20 МиБ; больше — H.264 CRF 20, звук
как есть). Прислать: `report.md`, из `moments3.txt` — всё о пролёте и строку ядерного подрыва, строки `Film: verdict`,
`replay played from the start`, `spectates`, ошибки (`Exception`, `ERROR`) лога съёмки; по кадру из середины и конца
пролёта (`ffmpeg -ss … -frames:v 1`).
