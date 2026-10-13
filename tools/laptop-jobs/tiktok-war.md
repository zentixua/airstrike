# Задание ноутбуку: война 06.10 — превью TikTok по моментам Артёма

Коммит zentixua/incoming — в сообщении координатора. Повтор — копия войны `2026-10-09T15_24_55` (Incoming 2.10.3);
моменты назвал Артём по шкале Flashback. На видеокарте — одна тяжёлая задача (между частями трейлера, не рядом с
ними), не во время игры Артёма (`pgrep -f 'instances/Airstrike Pack'` пусто). Инстанс, его миры и настройки не трогать.
Пути — от `/mnt/data/projects/airstrike`, `JAVA_HOME` — `java-runtime-delta` из Prism,
`W=/mnt/data/projects/airstrike/mod/run/claude-work`, `T=$W/tiktok/war`, `R=2026-10-09T15_24_55`.

Что снимается:
- 22:14 и 23:57 — глазами Артёма (`--at`: 10 с до, 5 с после); 23:57 — то же сбитие, что pov-0 глазами Мартина;
- 53:00–53:26 — ядерный подрыв (53:08) глазами Артёма в метро; тот же подрыв свободной камерой снаружи — кандидат
  «5. Ядерный удар» этого подрыва (объектив с места Артёма над землёй, запасная — штатив);
- 25:02–25:40 — пролёт свободной камеры из −102 121 −628 через −327 121 −572 (в 25:32) и дальше той же прямой; взгляд —
  на юг, повёрнутый до 25° к ударам окна (удар по порту 25:16–25:22 — в кадре), наклон — к ним.

## 1. Сборка, клиент, копия повтора
```sh
git fetch origin <коммит> && git worktree add --detach $W/tiktok-war-wt <коммит>; S=$W/tiktok-war-wt/tools
$W/tiktok-war-wt/mod/gradlew -p $S/replayscan build
G=$W/tiktok/game/2.10.3; J=$(ls $G/day/*.jar); echo "$J"            # клиент, снявший pov-0; jar мода — один
rm $G/mods/replayscan-*.jar && cp $S/replayscan/build/libs/replayscan-1.0.0.jar $G/mods/
grep enableShaders $G/config/iris.properties                        # true
unzip -p $W/replayscan/replays/$R.zip metadata.json | grep -c release-media/dh-airstrike-overworld   # 1
```
Клиента `$W/tiktok/game/2.10.3` нет — копия `$W/view-0610/2.10.3` с jar съёмки в `mods/` и jar мода дня в `day/`.
Копии повтора нет или путь DH в ней не тот — копия как в `replayscan.md` (подготовка, шаг 3): `patch_replay.py`
(DH → `$W/release-media/dh-airstrike-overworld`), затем `strip_payloads.py … pointblank:mainheldsimplifiedstatebroadcastpacket`.

## 2. Моменты и план
```sh
mkdir -p $T
uv run $S/clips/moments.py $W/replayscan/out/$R/replay.duckdb --out $T/clips --pov-top 50 \
  --at 22:14 --at 23:57 --at 53:00/53:26 \
  --fly 'at=25:02/25:40 from=-102,121,-628 to=-327,121,-572 to_at=25:32 look=south turn=25' | tee $T/moments.txt
NUKE=$(python3 -c "import json; print(','.join(c['id'] for c in json.load(open('$T/clips/candidates.json'))['nuke'] if 63600 <= c['t'] <= 64000))")
echo "$NUKE"                                                        # один id: подрыв 53:08
uv run $S/clips/plan.py $T/clips/candidates.json --all pov,fly --pick "$NUKE" --replays $W/replayscan/replays
```
В `$T/clips/candidates.md`: «6. Глазами игроков» — три отрезка глазами recorder (22:04–22:19, 23:47–24:02,
53:00–53:26, у третьего — «ядерный подрыв в N блоках вне кадра»), «7. Пролёт камеры» — один; в `moments.txt` — курс и
наклон пролёта и каждый удар окна (расстояние, в кадре ли). В кадре пролёта нет ни одного удара 25:16–25:22 —
шаг 2 ещё раз с `turn=45`; и тогда нет — пролёт не снимать (`plan.py … --all pov`), в отчёт — `moments.txt`.

## 3. Съёмка (видеокарта)
Окно — не размера кадра (по умолчанию 1280×720):
```sh
$S/laptop_job.sh tiktok-war -- python3 $S/prod_client.py --no-copy --dir $G --incoming-jar $J --jvm=-Xmx12g \
  --prop replayscan.film=$T/clips/film/$R/plan.json
```
Успех: в `$G/logs/latest.log` — `Film: done`; у каждого плана — `Film: verdict <план> <камера>: PASS` (в `verdict.json`
— `dh_world` 0), у отрезков глазами — `Film: the replay viewer spectates recorder on the replay server`; в `qa.jsonl`
пролёта и ядерки снаружи — `"dh":1`. Не прошедший план не доводить: в отчёт его `why`.

## 4. Монтаж и выдача
```sh
uv run $S/clips/edit.py $T/clips/film --out $T/clips/out --soon
```
Ролики `$T/clips/out/{pov-0,pov-1,pov-2,fly-0,<NUKE>}.mp4`, подписи `.txt` и `report.md` — в файлы проекта,
`tiktok-war/` (до 20 МиБ; больше — H.264 CRF 20, звук как есть). Прислать: `report.md`, `moments.txt`, строки
`Film: verdict` и `spectates`, ошибки (`Exception`, `ERROR`) лога съёмки.
