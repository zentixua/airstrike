# Задание ноутбуку: война 06.10 — превью TikTok, ночные проходы FPV мимо самых жарких ударов

Коммит zentixua/incoming — в сообщении координатора. Повтор — копия войны `2026-10-09T15_24_55` (Incoming 2.10.3).
На видеокарте — одна тяжёлая задача (между частями трейлера, не рядом с ними), не во время игры Артёма
(`pgrep -f 'instances/Airstrike Pack'` пусто). Инстанс, его миры и настройки не трогать.
Пути — от `/mnt/data/projects/airstrike`, `JAVA_HOME` — `java-runtime-delta` из Prism,
`W=/mnt/data/projects/airstrike/mod/run/claude-work`, `T=$W/tiktok/war`, `R=2026-10-09T15_24_55`.

`$T/clips`…`$T/clips5` — не трогать. Снимается в `$T/clips6`. Артём 13.10: ролик из нескольких проходов камеры, как у
FPV-дрона, ночью (время суток Flashback — огни снарядов видны), низко над самыми жаркими ударами; один проход — у
ударов по порту 25:17–25:21, остальные — где ударов больше всего за всю запись. Монтаж под музыку — в облаке, поэтому
каждый проход выдаётся отдельным видео. Прошлая съёмка (`clips5`): проходы над самым ударом влетали в его столб дыма
(кадр чёрный), а три из четырёх выходили низом кадра за чанки повтора; теперь проход — сбоку от удара, мимо дыма, в
чанках — и на самой высокой запасной камере, проходов — восемь (в ролик пойдут лучшие).

## 1. Сборка
```sh
git -C $W/tiktok-war-wt fetch origin <коммит> && git -C $W/tiktok-war-wt checkout --detach <коммит>; S=$W/tiktok-war-wt/tools
$W/tiktok-war-wt/mod/gradlew -p $S/replayscan build
G=$W/tiktok/game/2.10.3; J=$(ls $G/day/*.jar); echo "$J"            # клиент первой съёмки; jar мода — один
rm $G/mods/replayscan-*.jar && cp $S/replayscan/build/libs/replayscan-1.0.0.jar $G/mods/
```

## 2. Моменты и план
```sh
uv run $S/clips/moments.py $W/replayscan/out/$R/replay.duckdb --out $T/clips6 --pov-top 0 \
  --fpv 'search=25:14/25:26 near=-320,-405,90 shots=1 seconds=9 time=night' \
  --fpv 'shots=8 seconds=9 time=night' | tee $T/moments6.txt
uv run $S/clips/plan.py $T/clips6/candidates.json --all fly --replays $W/replayscan/replays
python3 -c "import json; s=json.load(open('$T/clips6/film/$R/plan.json'))['shots']; print([(x['name'], x['a'], x['b'], x.get('time')) for x in s])"
```
В `moments6.txt` — строки «FPV …» и «проход N: <с>–<по>: FPV мимо удара …» (до девяти проходов: один у порта в
25:1x и восемь самых жарких). У каждого плана в выводе последней команды `time` 18000. Проходов меньше — так и
снимать, в отчёт — строки «ни одним курсом и стороной».

## 3. Съёмка (видеокарта)
```sh
$S/laptop_job.sh tiktok-war6 -- python3 $S/prod_client.py --no-copy --dir $G --incoming-jar $J --jvm=-Xmx12g \
  --prop replayscan.film=$T/clips6/film/$R/plan.json
```
Успех: в `$G/logs/latest.log` — `Film: done`; перед каждым планом — `Film: <план>: replay played from the start to
tick …` (строки `replay stopped at tick … on the way` нет); у каждого — `Film: verdict <план> <камера>: PASS` (в
`verdict.json` — `dh_world` 0, `low_out` — до 2 % кадров); строки `effect particles stay` нет. Не прошедший план не
доводить: в отчёт его `why`.

## 4. Видео проходов и выдача
```sh
uv run $S/clips/edit.py $T/clips6/film --out $T/clips6/out --soon
mkdir -p $T/clips6/passes && python3 - <<EOF
import json, os, subprocess
for name, b in json.load(open("$T/clips6/film/$R/film/best.json")).items():
    # и не прошедшие проверку: ночью низ кадра за чанками бывает не виден — решает монтаж по кадрам
    if b.get("dir") and os.path.exists(b["dir"] + "/video.mkv"):
        out = "$T/clips6/passes/" + name + ("" if b.get("pass") else "-FAIL") + ".mp4"
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", b["dir"] + "/video.mkv", "-c:v", "libx264", "-preset", "slow",
                        "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", out], check=True)
        print(name, b.get("candidate"), b.get("pass"))
EOF
ls -la $T/clips6/passes
```
В файлы проекта: каждый `$T/clips6/passes/*.mp4` (проход ~9 с; больше 20 МиБ — CRF 23), `report.md`, `moments6.txt`.
Прислать: `report.md`, строки «проход» из `moments6.txt`, строки `Film: verdict`, `replay played from the start`,
ошибки (`Exception`, `ERROR`) лога съёмки; по кадру из 1, 3, 5 и 7 с каждого прохода (`ffmpeg -ss <с> -frames:v 1`).
