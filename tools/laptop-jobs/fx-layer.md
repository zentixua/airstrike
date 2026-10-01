# Ноутбук: свой слой дыма и огня — залп РСЗО рядом, разрывы за 1,5 км (одна задача)

Тред «Вспышки и дым», PR #183, ветка `claude/project-thread-v1ahfv`. Кандидат — один коммит: координатор вписывает
его полный SHA вместо `<SHA>` во **все** блоки ниже перед отправкой (и `SHA7` — первые 7 знаков). Артём в это время
не играет.

**Что проверяем.** Частицы мода и дальняя картинка теперь рисуются одним проходом после мира, от дальних к ближним,
с глубиной Minecraft и Distant Horizons. Жалоба Артёма: дым пуска «Града» в 10–20 блоках оказывался позади столбов
разрывов в сотнях блоков, а дальний дым — поверх LOD DH. Со сборкой Артёма (DH, Iris с шейдерами, ~220 модов) на копии
Greenfield, без видео: зритель на площадке над городом, пусковая РСЗО в ~20 блоках перед ним, залп по точке в 1,5 км
к северу. Строки `SCENARIO far` раз в секунду: `слой: квадратов N, X мс (самый долгий за секунду …; без сборки мусора …,
процессор …; кадров со сборкой …), глубина: …` — время слоя в потоке отрисовки и чем закрыт дым (мир, мир и DH).

## Правила (для каждого блока)
- Каждый блок — **одним вызовом, как написан**; состояние оболочки между вызовами не сохраняется. Блоки с шага 2
  начинаются с `W=… && cd "${W:?}"`.
- Папка — ровно `/mnt/data/projects/airstrike/mod/run/claude-work/fx-layer-SHA7` (новая). Уже есть — стоп, сообщить
  координатору, ничего не удалять и не перезаписывать.
- Инстанс Артёма, его миры и настройки не трогать: `prod_client.py` копирует инстанс и мир в `$W/mod/run/prod`
  (площадка `fill` ставится только в копии мира).
- Клиент — только через `tools/nested_kwin.sh` (это делает `prod_client.py`); тяжёлое — только `tools/laptop_job.sh`;
  одна задача за раз.
- Остановить задачу — только `systemctl --user stop 'airstrike-job-fx-layer-*'`. Никаких `pkill`/`killall`/`kill`
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
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/fx-layer-SHA7" && echo "стоп: папка fx-layer-SHA7 уже есть"
MC="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/All of Create Aeronautics/minecraft"
ls "$MC/saves" | grep -x "Greenfield v0.5.4" || echo "стоп: нет мира Greenfield v0.5.4"
du -sh "$MC/saves/Greenfield v0.5.4" "$MC/mods"; df -h /mnt/data/projects/airstrike/mod/run
```
Свободно — не меньше Greenfield + mods + 5 ГБ.

## 2. Подготовка: worktree, сборка
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/fx-layer-SHA7" && R=/mnt/data/projects/airstrike && cd "${R:?}" && \
git fetch origin claude/project-thread-v1ahfv && git worktree add "$W" <SHA> && cd "${W:?}" && mkdir -p "$W/mod/run/fxl" && \
findmnt -n -o ID "/run/user/$(id -u)/doc" > "$W/mod/run/fxl/doc-mount.before" && echo "маунт doc записан" && \
git log --oneline -1 && [ "$(git rev-parse HEAD)" = <SHA> ] && echo "коммит верный" && \
grep -q 'глубина: ' mod/src/main/java/ua/zentix/airstrike/client/fx/layer/FxLayer.java && echo "слой на месте"
```
Должно быть «маунт doc записан», «коммит верный» и «слой на месте». Нет — стоп, прислать вывод.

Сборка тестового jar (в фоне, тайм-аут вызова 25 мин; код не 0 — стоп, сообщить):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/fx-layer-SHA7" && cd "${W:?}/mod" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 20m ../tools/laptop_job.sh fx-layer-build -- ./gradlew scenarioJar -q --console=plain; echo "код $?"; ls -l build/scenario-libs/
```

## A. Прогон (один, без видео; в фоне, тайм-аут вызова 25 мин)
Площадка из гладкого камня на высоте 149 над городом (81×61, только в копии мира). Зритель в спектаторе на
`-272 150 -950`. Перед пуском он на миг смотрит на юг (`tp … 0 6`): пусковая встаёт позади стреляющего — значит,
к северу от него, в ~20 блоках; после команды — снова на север (`tp … 180 6`), пусковая в кадре справа, залп уходит
к цели `-272 64 -2442` (1,5 км). `wait:blast` — шаги ждут следующего взрыва на сервере (не дольше 3600 тиков). Команда
ждёт 40 тиков, кадр — 20: `launch_1` — ~40 тиков после команды залпа, `launch_2` — ещё через 80; `far_1` — 2-й тик после
первого разрыва, `far_2` — 52-й, `far_3` — 172-й, `far_4` — 392-й; `side` — взгляд на северо-восток.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/fx-layer-SHA7" && cd "${W:?}" && touch mod/run/fxl/.step-start && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
echo "начало: $(date -u +%T) UTC" && timeout -k 60 20m tools/laptop_job.sh fx-layer -- python3 tools/prod_client.py commands --world "Greenfield v0.5.4" --seconds 1000 \
  --size 1920x1080 \
  --prop 'airstrike.commands=hud:off;gamemode spectator;gamerule doDaylightCycle false;gamerule doWeatherCycle false;time set 6000;weather clear;tp @s -272 150 -950 180 6;fill -312 149 -990 -232 149 -930 minecraft:smooth_stone;wait:600;shot:base;tp @s -272 150 -950 0 6;airstrike salvo rocket 30 20 at -272 64 -2442;tp @s -272 150 -950 180 6;shot:launch_1;wait:60;shot:launch_2;wait:blast;wait:2;shot:far_1;wait:30;shot:far_2;wait:100;shot:far_3;wait:200;shot:far_4;tp @s -272 150 -950 135 6;shot:side;wait:200'; \
code=$?; echo "код $code, конец: $(date -u +%T) UTC"; case "$code" in 124|137) systemctl --user stop 'airstrike-job-fx-layer-*';; esac; true
```
Наблюдатель: в `$W/mod/run/prod/logs/latest.log` идут строки `SCENARIO …`, в конце `SCENARIO done`.

## В. Лог, кадры, выжимка (сразу после прогона, одним вызовом)
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/fx-layer-SHA7" && cd "${W:?}" && D=mod/run/fxl && P=mod/run/prod && mkdir -p "$D/logs" "$D/frames" && \
G=$(find "$P/logs" -maxdepth 1 -name '*.log.gz' -newer "$D/.step-start" | sort -V) && \
{ for g in $G; do zcat "$g"; done; cat "$P/logs/latest.log"; } > "$D/logs/full.log" && cp -pn "$P/logs/gc.log" "$D/logs/" 2>/dev/null; \
for f in $(find "$P/screenshots" -maxdepth 1 -name '*.png' -newer "$D/.step-start" | sort); do n=$(basename "$f" .png); ffmpeg -loglevel error -y -i "$f" -q:v 3 "$D/frames/$n.jpg"; done; \
ls "$D/frames"; grep -a -c 'SCENARIO done' "$D/logs/full.log"; \
python3 tools/logscan.py "$D/logs/full.log" --all > "$D/logscan.txt"; wc -lc "$D/logscan.txt"; \
grep -aE 'SCENARIO (commands|fps|/airstrike)|Удар: |Слой эффектов|Шейдер|Текстура эффектов|Distant Horizons|has crashed|emergencySaveAndCrash|Unreported exception' "$D/logs/full.log" | cut -c1-300 > "$D/lines.txt"; wc -lc "$D/lines.txt"; \
grep -a 'SCENARIO far' "$D/logs/full.log" | grep -aoE '^\[[^]]*\]|слой: .*' | paste -sd' ' | sed 's/ \[/\n[/g' > "$D/layer-times.txt"; wc -lc "$D/layer-times.txt"; \
awk '{for(i=1;i<=NF;i++){v=$(i+1); sub(/[,;)]$/,"",v); if($i=="квадратов" && v+0>q) q=v+0; if($i=="мусора" && v+0>m) m=v+0; if($i=="процессор" && v+0>c) c=v+0}} END{print "квадратов больше всего: " q "; наибольший кадр без сборки мусора: " m " мс; наибольшее время процессора в таком кадре: " c " мс"}' "$D/layer-times.txt"; \
grep -aoE 'глубина: [^;)]*' "$D/layer-times.txt" | sort | uniq -c; \
grep -aE 'Pause (Young|Old|Full|Remark|Cleanup)' "$D/logs/gc.log" 2>/dev/null | awk '{ms=$NF; sub(/ms$/,"",ms); if (ms+0>5) print}' > "$D/gc-pauses.txt"; echo "пауз GC > 5 мс: $(wc -l < "$D/gc-pauses.txt")"; \
now=$(findmnt -n -o ID "/run/user/$(id -u)/doc"); [ -n "$now" ] && [ "$now" = "$(cat "$D/doc-mount.before")" ] && echo "маунт doc цел: $now" || { echo "ПРОВАЛ: маунт /run/user/$(id -u)/doc пропал или сменился (был $(cat "$D/doc-mount.before"), стал ${now:-нет})"; false; }
```

## Что прислать координатору
0. Вывод шагов 1, 2 и код и время прогона.
1. `mod/run/fxl/lines.txt` и `mod/run/fxl/layer-times.txt` целиком (до 40 КБ), строку «квадратов больше всего …» и
   счёт строк «глубина: …».
2. logscan: ошибки, исключения, `Mixin`, `AccessTransformer`, GL (`GlDebug`, `OpenGL debug`) — или «ноль»;
   `gc-pauses.txt` (паузы > 5 мс; больше 60 строк — первые и последние 30).
3. Кадры — все JPEG из `mod/run/fxl/frames/` в `/mnt/project-files/fx-layer/laptop/` (SendUserFile), по строке на кадр.

## Проходит, если
- прогон кончается сам с **кодом 0**, `SCENARIO done` есть, нет `has crashed`/`emergencySaveAndCrash`/`Unreported exception`;
- маунт порталов flatpak `/run/user/<uid>/doc` цел: шаг В пишет «маунт doc цел» с тем же ID, что в шаге 2;
- строка «Слой эффектов: прогрев N мс» — один раз, при входе в мир; строк «Шейдер … не загрузился», «Текстура
  эффектов … не найдена/не прочиталась», «Distant Horizons … API» нет;
- `wait:blast` дождалось взрыва (нет строки `взрыва нет`);
- в строках `SCENARIO far` с DH в кадре — «глубина: мир и DH» (не «мир» и не «нет»);
- время слоя: «наибольшее время процессора» в кадре без сборки мусора — **не больше 3 мс** при самом большом числе
  квадратов прогона; кадры длиннее 3 мс с процессором меньше 3 мс (поток ждал драйвер или его вытеснили) — перечислить
  со временем; секунды, где «самый долгий за секунду» больше, а «без сборки мусора» — нет, — с «кадров со сборкой» ≥ 1
  и паузой той же длины в `gc-pauses.txt` (время gc.log — местное, лог игры — UTC+3);
- logscan — без новых ошибок; исключений со `ua.zentix` в стеке нет;
- кадры: `launch_1`/`launch_2` — дым и пламя пуска у пусковой справа, клубы мягко тают у площадки (без резкой линии
  среза по камню), не мигают; `far_1..far_4` — столбы разрывов вдали **позади** дыма пуска: где дым пуска перекрывает
  их, он их закрывает; низ дальних столбов закрыт крышами города (LOD DH), а не рисуется поверх них; `side` — то же
  сбоку; не видно краёв квадрата клуба (ступеньки пикселей картинки вблизи — как у ванильных частиц, это норма), нет
  чёрных или белых квадратов, нет тёмной каймы вокруг клубов.
