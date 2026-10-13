# Задание ноутбуку: война 06.10 — превью TikTok, ночной пролёт у порта

Коммит zentixua/incoming — в сообщении координатора. Повтор — копия войны `2026-10-09T15_24_55` (Incoming 2.10.3).
На видеокарте — одна тяжёлая задача (между частями трейлера, не рядом с ними), не во время игры Артёма
(`pgrep -f 'instances/Airstrike Pack'` пусто). Инстанс, его миры и настройки не трогать.
Пути — от `/mnt/data/projects/airstrike`, `JAVA_HOME` — `java-runtime-delta` из Prism,
`W=/mnt/data/projects/airstrike/mod/run/claude-work`, `T=$W/tiktok/war`, `R=2026-10-09T15_24_55`.

`$T/clips`, `$T/clips2`, `$T/clips3` — не трогать. Снимается в `$T/clips4` только пролёт. Артём 13.10: тот же путь
от контейнерного порта к пристани с яхтами, но ночью (время суток Flashback — огни снарядов видны) и где ударов
больше: moments.py ищет по всей записи окно, где пролёт весь в чанках повтора и ударов в кадре больше всего.

## 1. Сборка
```sh
git -C $W/tiktok-war-wt fetch origin <коммит> && git -C $W/tiktok-war-wt checkout --detach <коммит>; S=$W/tiktok-war-wt/tools
$W/tiktok-war-wt/mod/gradlew -p $S/replayscan build
G=$W/tiktok/game/2.10.3; J=$(ls $G/day/*.jar); echo "$J"            # клиент первой съёмки; jar мода — один
rm $G/mods/replayscan-*.jar && cp $S/replayscan/build/libs/replayscan-1.0.0.jar $G/mods/
```

## 2. Моменты и план
```sh
uv run $S/clips/moments.py $W/replayscan/out/$R/replay.duckdb --out $T/clips4 --pov-top 0 \
  --fly 'at=25:02/25:40 from=-102,121,-628 to=-327,121,-572 to_at=25:32 look=south turn=30 search=00:00/60:30 time=night' \
  | tee $T/moments4.txt
uv run $S/clips/plan.py $T/clips4/candidates.json --all fly --replays $W/replayscan/replays
python3 -c "import json; s=json.load(open('$T/clips4/film/$R/plan.json'))['shots']; print([(x['name'], x['a'], x['b'], x.get('time')) for x in s])"
```
В `moments4.txt` у пролёта — строка «поиск 00:00–60:30: больше всего ударов в кадре — <с>–<по> (N, по земле M)»,
«в чанках повтора — K из K проверок», удары окна «в кадре». В плане у пролёта `time` 18000. Строки «окна в чанках
повтора с ударами в кадре нет» — пролёт не снимать, в отчёт — `moments4.txt`.

## 3. Съёмка (видеокарта)
```sh
$S/laptop_job.sh tiktok-war4 -- python3 $S/prod_client.py --no-copy --dir $G --incoming-jar $J --jvm=-Xmx12g \
  --prop replayscan.film=$T/clips4/film/$R/plan.json
```
Успех: в `$G/logs/latest.log` — `Film: done`; перед планом — `Film: <план>: replay played from the start to tick …`
(строки `replay stopped at tick … on the way` нет); `Film: verdict <план> <камера>: PASS` (в `verdict.json` —
`dh_world` 0, `low_out` — до 2 % кадров); строки `effect particles stay` нет. Не прошедший план не доводить: в отчёт
его `why`.

## 4. Монтаж и выдача
```sh
uv run $S/clips/edit.py $T/clips4/film --out $T/clips4/out --soon
```
Ролик `$T/clips4/out/fly-0.mp4`, подпись `.txt` и `report.md` — в файлы проекта (до 20 МиБ; больше — H.264 CRF 20,
звук как есть). Прислать: `report.md`, `moments4.txt`, строки `Film: verdict`, `replay played from the start`, ошибки
(`Exception`, `ERROR`) лога съёмки; кадры из начала, середины и конца пролёта (`ffmpeg -ss … -frames:v 1`).
