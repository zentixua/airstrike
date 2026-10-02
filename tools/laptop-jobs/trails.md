# Ноутбук: шлейфы лентой — залп РСЗО рядом и сбоку (одна задача)

Тред «Вспышки и дым», шаг 2, ветка `claude/project-thread-v1ahfv`. Кандидат — один коммит: координатор вписывает его
полный SHA вместо `<SHA>` во **все** блоки ниже перед отправкой (и `SHA7` — первые 7 знаков). Артём в это время
не играет.

**Что проверяем.** Шлейф любого снаряда теперь — одна лента по точкам пути от сопел (вблизи и вдали одинаково), поверх
густых следов — по одному клубу объёма за тик. Жалоба Артёма: в залпах дым «поворачивался боком» — клубы следа
переполняли группу частиц, и след рвался на вытянутые куски вдоль курса. Со сборкой Артёма (DH, Iris с шейдерами,
~220 модов) на копии Greenfield, без видео: та же площадка, что в fx-layer, залп 30 ракет РСЗО к цели в 1,5 км; кадры
спереди (у пусковой) и сбоку (весь путь залпа). Строки `SCENARIO far` раз в секунду: `кадр: … лент N …; слой:
квадратов N, X мс (…)` — сколько отрезков лент и время слоя.

**Второй прогон.** Первый (5873001): ленты ровные, но сбоку на них лежали тёмные клубы объёма — бусины. Причина:
пусковая была дальше прорисовки, у клиента там нет чанков, и частица брала свет ванили «чанка нет — темно»; а туман
Minecraft красил частицу в свой цвет, не делая её прозрачной (в облаке — светлые бусины). Теперь вне чанков клиента —
свет открытого неба, как у ленты, а туман частицу гасит. Дуг через всё небо не будет и не должно быть: двигатель
снаряда «Града» горит 2 с (`RocketEntity.BURN_TICKS` = 40), дальше полёт без дыма — след только у пусковой.

## Правила (для каждого блока)
- Каждый блок — **одним вызовом, как написан**; состояние оболочки между вызовами не сохраняется. Блоки с шага 2
  начинаются с `W=… && cd "${W:?}"`.
- Папка — ровно `/mnt/data/projects/airstrike/mod/run/claude-work/trails-SHA7` (новая). Уже есть — стоп, сообщить
  координатору, ничего не удалять и не перезаписывать.
- Инстанс Артёма, его миры и настройки не трогать: `prod_client.py` копирует инстанс и мир в `$W/mod/run/prod`
  (площадка `fill` ставится только в копии мира).
- Клиент — только через `tools/nested_kwin.sh` (это делает `prod_client.py`); тяжёлое — только `tools/laptop_job.sh`;
  одна задача за раз.
- Остановить задачу — только `systemctl --user stop 'airstrike-job-trails-*'`. Никаких `pkill`/`killall`/`kill`
  по имени, `./gradlew --stop`, `rm -rf`, `-f`/`--force`.
- Смерть прогона — координатору в течение 5 мин: последние 40 строк лога и `crash-reports/`. Прогон не повторять
  без слова координатора.
- Результат — только текст (до ~40 КБ на сообщение) и названные кадры (SendUserFile). Логи и копия мира остаются
  в `$W`; уборку решает Артём словами в треде «Ноутбук».

## 1. Проверка перед запуском
Любой вывод у первых трёх команд, строка «стоп» — не запускать, сообщить координатору.
```sh
pgrep -a -x java | grep -Ei 'neoforge|minecraft'
pgrep -a -x prismlauncher
systemctl --user list-units 'airstrike-job-*' --state=active --no-legend
SHA=<SHA>; [ ${#SHA} = 40 ] || echo "стоп: SHA не вписан"
echo "маунт doc: $(findmnt -n -o ID "/run/user/$(id -u)/doc" || echo "стоп: маунта /run/user/$(id -u)/doc нет")"
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/trails-SHA7" && echo "стоп: папка trails-SHA7 уже есть"
MC="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/All of Create Aeronautics/minecraft"
ls "$MC/saves" | grep -x "Greenfield v0.5.4" || echo "стоп: нет мира Greenfield v0.5.4"
du -sh "$MC/saves/Greenfield v0.5.4" "$MC/mods"; df -h /mnt/data/projects/airstrike/mod/run
```
Свободно — не меньше Greenfield + mods + 5 ГБ.

## 2. Подготовка: worktree, сборка
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/trails-SHA7" && R=/mnt/data/projects/airstrike && cd "${R:?}" && \
git fetch origin claude/project-thread-v1ahfv && git worktree add "$W" <SHA> && cd "${W:?}" && mkdir -p "$W/mod/run/trl" && \
findmnt -n -o ID "/run/user/$(id -u)/doc" > "$W/mod/run/trl/doc-mount.before" && echo "маунт doc записан" && \
git log --oneline -1 && [ "$(git rev-parse HEAD)" = <SHA> ] && echo "коммит верный" && \
grep -q 'TONE' mod/src/main/java/ua/zentix/airstrike/client/far/FarFlightView.java && echo "ленты на месте"
```
Должно быть «маунт doc записан», «коммит верный» и «ленты на месте». Нет — стоп, прислать вывод.

Сборка тестового jar (в фоне, тайм-аут вызова 25 мин; код не 0 — стоп, сообщить):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/trails-SHA7" && cd "${W:?}/mod" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 20m ../tools/laptop_job.sh trails-build -- ./gradlew scenarioJar -q --console=plain; echo "код $?"; ls -l build/scenario-libs/
```

## A. Прогон (один, без видео; в фоне, тайм-аут вызова 25 мин)
Площадка и пусковая — как в fx-layer: площадка из гладкого камня на высоте 149 над городом (только в копии мира),
зритель в спектаторе пускает с `-272 150 -950`, на миг глядя на юг, — пусковая встаёт к северу от него, в ~20 блоках.
Спереди он смотрит с `-272 165 -950` на север (`tp … 180 10`): `front_1` — ~60 тиков после команды залпа (ленты над
пусковой и уходящие к цели), `front_2` — ещё через 60. Потом сбоку: с `78 300 -1340` на запад, на 10° вверх (`tp … 90 -10`), в 350 блоках
от пути залпа: следы разгона — слева внизу, у пусковой (она дальше прорисовки), сами ракеты — точками выше —
`side_1`, через 60 тиков `side_2`, через 60 `side_3`. `wait:blast` — шаги ждут взрыва на сервере
(не дольше 3600 тиков), `side_4` — 100 тиков после первого разрыва (ленты тают, концы сходят на нет), `front_3` — снова
спереди ещё через 200. Команда ждёт 40 тиков, кадр — 20.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/trails-SHA7" && cd "${W:?}" && touch mod/run/trl/.step-start && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
echo "начало: $(date -u +%T) UTC" && timeout -k 60 20m tools/laptop_job.sh trails -- python3 tools/prod_client.py commands --world "Greenfield v0.5.4" --seconds 1000 \
  --size 1920x1080 \
  --prop 'airstrike.commands=hud:off;gamemode spectator;gamerule doDaylightCycle false;gamerule doWeatherCycle false;time set 6000;weather clear;tp @s -272 150 -950 180 6;fill -312 149 -990 -232 149 -930 minecraft:smooth_stone;tp @s -272 165 -950 180 10;wait:600;shot:base;tp @s -272 150 -950 0 6;airstrike salvo rocket 30 20 at -272 64 -2442;tp @s -272 165 -950 180 10;shot:front_1;wait:40;shot:front_2;tp @s 78 300 -1340 90 -10;shot:side_1;wait:40;shot:side_2;wait:40;shot:side_3;wait:blast;wait:80;shot:side_4;tp @s -272 165 -950 180 10;wait:180;shot:front_3;wait:100'; \
code=$?; echo "код $code, конец: $(date -u +%T) UTC"; case "$code" in 124|137) systemctl --user stop 'airstrike-job-trails-*';; esac; true
```
Наблюдатель: в `$W/mod/run/prod/logs/latest.log` идут строки `SCENARIO …`, в конце `SCENARIO done`.

## В. Лог, кадры, выжимка (сразу после прогона, одним вызовом)
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/trails-SHA7" && cd "${W:?}" && D=mod/run/trl && P=mod/run/prod && mkdir -p "$D/logs" "$D/frames" && \
G=$(find "$P/logs" -maxdepth 1 -name '*.log.gz' -newer "$D/.step-start" | sort -V) && \
{ for g in $G; do zcat "$g"; done; cat "$P/logs/latest.log"; } > "$D/logs/full.log" && cp -pn "$P/logs/gc.log" "$D/logs/" 2>/dev/null; \
for f in $(find "$P/screenshots" -maxdepth 1 -name '*.png' -newer "$D/.step-start" | sort); do n=$(basename "$f" .png); ffmpeg -loglevel error -y -i "$f" -q:v 3 "$D/frames/$n.jpg"; done; \
ls "$D/frames"; grep -a -c 'SCENARIO done' "$D/logs/full.log"; \
python3 tools/logscan.py "$D/logs/full.log" --all > "$D/logscan.txt"; wc -lc "$D/logscan.txt"; \
grep -aE 'SCENARIO (commands|fps|/airstrike)|Удар: |Слой эффектов|Шейдер|Текстура эффектов|Distant Horizons|has crashed|emergencySaveAndCrash|Unreported exception' "$D/logs/full.log" | cut -c1-300 > "$D/lines.txt"; wc -lc "$D/lines.txt"; \
grep -a 'SCENARIO far' "$D/logs/full.log" | grep -aoE '^\[[^]]*\]|лент [0-9]+|слой: .*' | paste -sd' ' | sed 's/ \[/\n[/g' > "$D/layer-times.txt"; wc -lc "$D/layer-times.txt"; \
awk '{for(i=1;i<=NF;i++){v=$(i+1); sub(/[,;)]$/,"",v); if($i=="лент" && v+0>l) l=v+0; if($i=="квадратов" && v+0>q) q=v+0; if($i=="мусора" && v+0>m) m=v+0; if($i=="процессор" && v+0>c) c=v+0}} END{print "лент больше всего: " l "; квадратов больше всего: " q "; наибольший кадр без сборки мусора: " m " мс; наибольшее время процессора в таком кадре: " c " мс"}' "$D/layer-times.txt"; \
grep -aE 'Pause (Young|Old|Full|Remark|Cleanup)' "$D/logs/gc.log" 2>/dev/null | awk '{ms=$NF; sub(/ms$/,"",ms); if (ms+0>5) print}' > "$D/gc-pauses.txt"; echo "пауз GC > 5 мс: $(wc -l < "$D/gc-pauses.txt")"; \
now=$(findmnt -n -o ID "/run/user/$(id -u)/doc"); [ -n "$now" ] && [ "$now" = "$(cat "$D/doc-mount.before")" ] && echo "маунт doc цел: $now" || { echo "ПРОВАЛ: маунт /run/user/$(id -u)/doc пропал или сменился (был $(cat "$D/doc-mount.before"), стал ${now:-нет})"; false; }
```

## Что прислать координатору
0. Вывод шагов 1, 2 и код и время прогона.
1. `mod/run/trl/lines.txt` и `mod/run/trl/layer-times.txt` целиком (до 40 КБ), строку «лент больше всего …».
2. logscan: ошибки, исключения, `Mixin`, `AccessTransformer`, GL (`GlDebug`, `OpenGL debug`) — или «ноль»;
   `gc-pauses.txt` (паузы > 5 мс; больше 60 строк — первые и последние 30).
3. Кадры — все JPEG из `mod/run/trl/frames/` в `/mnt/project-files/fx-layer/trails-laptop/` (SendUserFile), по строке
   на кадр.

## Проходит, если
- прогон кончается сам с **кодом 0**, `SCENARIO done` есть, нет `has crashed`/`emergencySaveAndCrash`/`Unreported exception`;
- маунт порталов flatpak `/run/user/<uid>/doc` цел: шаг В пишет «маунт doc цел» с тем же ID, что в шаге 2;
- строка «Слой эффектов: прогрев N мс» — один раз; строк «Шейдер … не загрузился», «Текстура эффектов … не найдена/не
  прочиталась» нет; `wait:blast` дождалось взрыва (нет строки `взрыва нет`);
- время слоя: «наибольшее время процессора» в кадре без сборки мусора — **не больше 3 мс** при самом большом числе
  лент прогона; кадры длиннее 3 мс с процессором меньше 3 мс — перечислить со временем;
- logscan — без новых ошибок; исключений со `ua.zentix` в стеке нет;
- кадры: `front_1`/`front_2` — над пусковой следы ракет — ровные серые ленты с неровным краем, от сопел без разрыва;
  `side_1..side_3` — следы разгона у пусковой ровными лентами, **целые** (не рвутся на вытянутые куски вдоль курса,
  не «клубы боком»), не чёрные и не белые полосы, **без бусин** — ни тёмных, ни светлых клубов на ленте — и без
  поперечных ступенек на изгибе; `side_4` и `front_3` —
  следы тают, концы сходят на нет (не обрезаны поперёк), разрывы у цели видны вдали; цвет лент — как у дыма пуска рядом
  (не темнее и не ярче заметно), под шейдерами тоже.
