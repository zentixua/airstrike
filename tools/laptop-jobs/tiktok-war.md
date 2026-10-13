# Задание ноутбуку: война 06.10 — превью TikTok, ночные проходы FPV над ударами

Коммит zentixua/incoming — в сообщении координатора. Повтор — копия войны `2026-10-09T15_24_55` (Incoming 2.10.3).
На видеокарте — одна тяжёлая задача (между частями трейлера, не рядом с ними), не во время игры Артёма
(`pgrep -f 'instances/Airstrike Pack'` пусто). Инстанс, его миры и настройки не трогать.
Пути — от `/mnt/data/projects/airstrike`, `JAVA_HOME` — `java-runtime-delta` из Prism,
`W=/mnt/data/projects/airstrike/mod/run/claude-work`, `T=$W/tiktok/war`, `R=2026-10-09T15_24_55`.

`$T/clips`…`$T/clips3` — не трогать. Снимается в `$T/clips5`. Артём 13.10: ролик из нескольких проходов камеры, как у
FPV-дрона, ночью (время суток Flashback — огни снарядов видны), низко над самыми жаркими ударами; один проход — у
ударов по порту 25:17–25:21, остальные — где ударов больше всего за всю запись. Монтаж под музыку — в облаке, поэтому
каждый проход выдаётся отдельным видео.

## 1. Сборка
```sh
git -C $W/tiktok-war-wt fetch origin <коммит> && git -C $W/tiktok-war-wt checkout --detach <коммит>; S=$W/tiktok-war-wt/tools
$W/tiktok-war-wt/mod/gradlew -p $S/replayscan build
G=$W/tiktok/game/2.10.3; J=$(ls $G/day/*.jar); echo "$J"            # клиент первой съёмки; jar мода — один
rm $G/mods/replayscan-*.jar && cp $S/replayscan/build/libs/replayscan-1.0.0.jar $G/mods/
```

## 2. Моменты и план
```sh
uv run $S/clips/moments.py $W/replayscan/out/$R/replay.duckdb --out $T/clips5 --pov-top 0 \
  --fpv 'search=25:00/25:40 near=-215,-600,500 shots=1 seconds=9 time=night' \
  --fpv 'shots=4 seconds=9 time=night' | tee $T/moments5.txt
uv run $S/clips/plan.py $T/clips5/candidates.json --all fly --replays $W/replayscan/replays
python3 -c "import json; s=json.load(open('$T/clips5/film/$R/plan.json'))['shots']; print([(x['name'], x['a'], x['b'], x.get('time')) for x in s])"
```
В `moments5.txt` — строки «FPV …» и «проход N: <с>–<по>: FPV над ударом …» (пять проходов: один у порта в 25:1x и
четыре самых жарких). У каждого плана в выводе последней команды `time` 18000. Проходов меньше — так и снимать, в
отчёт — строки «ни одним курсом не в чанках повтора».

## 3. Съёмка (видеокарта)
```sh
$S/laptop_job.sh tiktok-war5 -- python3 $S/prod_client.py --no-copy --dir $G --incoming-jar $J --jvm=-Xmx12g \
  --prop replayscan.film=$T/clips5/film/$R/plan.json
```
Успех: в `$G/logs/latest.log` — `Film: done`; перед каждым планом — `Film: <план>: replay played from the start to
tick …` (строки `replay stopped at tick … on the way` нет); у каждого — `Film: verdict <план> <камера>: PASS` (в
`verdict.json` — `dh_world` 0, `low_out` — до 2 % кадров); строки `effect particles stay` нет. Не прошедший план не
доводить: в отчёт его `why`.

## 4. Видео проходов и выдача
```sh
uv run $S/clips/edit.py $T/clips5/film --out $T/clips5/out --soon
mkdir -p $T/clips5/passes && python3 - <<EOF
import json, subprocess
for name, b in json.load(open("$T/clips5/film/$R/film/best.json")).items():
    if b.get("pass") and b.get("dir"):
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", b["dir"] + "/video.mkv", "-c:v", "libx264", "-preset", "slow",
                        "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "$T/clips5/passes/" + name + ".mp4"], check=True)
        print(name, b.get("candidate"))
EOF
ls -la $T/clips5/passes
```
В файлы проекта: каждый `$T/clips5/passes/*.mp4` (проход ~9 с; больше 20 МиБ — CRF 23), `$T/clips5/out/fly-*.mp4`
(склейка без музыки), `report.md`, `moments5.txt`. Прислать: `report.md`, строки «проход» из `moments5.txt`, строки
`Film: verdict`, `replay played from the start`, ошибки (`Exception`, `ERROR`) лога съёмки; по кадру из середины
каждого прохода (`ffmpeg -ss 4.5 -frames:v 1`).
