# Ноутбук: огненный шар и вспышка взрывов днём и ночью (одна задача)

Тред «Вспышки и дым», шаг 3. Кандидат — один коммит: координатор вписывает его полный SHA вместо `<SHA>` во **все**
блоки ниже перед отправкой (и `SHA7` — первые 7 знаков). Артём в это время не играет.

**Что проверяем.** Жалоба Артёма: вспышки взрывов «топорные, особенно днём… оранжевые кольца». Теперь огненный шар —
анимация из 16 кадров (цвет по температуре, размер и время шара — из той же таблицы, что дальняя картинка), белого
ударного кольца и кольца пыли больше нет, дым вокруг шара светится, только пока светит шар. Днём — яркое ядро без
засветки экрана, ночью — вспышка на экране и зарево. Блик вдали рисуется «в глазу» и не режется рельефом или LOD.
Со сборкой Артёма (DH, Iris с шейдерами, ~220 модов) на копии Greenfield, без видео: площадка из fx-layer, по три
удара днём и ночью — ракета в 150 блоках (частицы, в прорисовке), шахед в 400 (частицы за прорисовкой и дальняя
картинка), ракета в 1,5 км (только дальняя картинка). Строки `SCENARIO far` раз в секунду: `ярче всех: ядро … px,
пересвет …, блик … px, вуаль … px` и время слоя.

## Правила (для каждого блока)
- Каждый блок — **одним вызовом, как написан**; состояние оболочки между вызовами не сохраняется. Блоки с шага 2
  начинаются с `W=… && cd "${W:?}"`.
- Папка — ровно `/mnt/data/projects/airstrike/mod/run/claude-work/fireball-SHA7` (новая). Уже есть — стоп, сообщить
  координатору, ничего не удалять и не перезаписывать.
- Инстанс Артёма, его миры и настройки не трогать: `prod_client.py` копирует инстанс и мир в `$W/mod/run/prod`
  (площадка `fill` ставится только в копии мира).
- Клиент — только через `tools/nested_kwin.sh` (это делает `prod_client.py`); тяжёлое — только `tools/laptop_job.sh`;
  одна задача за раз.
- Остановить задачу — только `systemctl --user stop 'airstrike-job-fireball-*'`. Никаких `pkill`/`killall`/`kill`
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
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/fireball-SHA7" && echo "стоп: папка fireball-SHA7 уже есть"
MC="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/All of Create Aeronautics/minecraft"
ls "$MC/saves" | grep -x "Greenfield v0.5.4" || echo "стоп: нет мира Greenfield v0.5.4"
grep -E '^(renderDistance|simulationDistance):' "$MC/options.txt"
du -sh "$MC/saves/Greenfield v0.5.4" "$MC/mods"; df -h /mnt/data/projects/airstrike/mod/run
```
Свободно — не меньше Greenfield + mods + 5 ГБ.

## 2. Подготовка: worktree, сборка
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/fireball-SHA7" && R=/mnt/data/projects/airstrike && cd "${R:?}" && \
git fetch origin claude/project-thread-v1ahfv && git worktree add "$W" <SHA> && cd "${W:?}" && mkdir -p "$W/mod/run/fbl" && \
findmnt -n -o ID "/run/user/$(id -u)/doc" > "$W/mod/run/fbl/doc-mount.before" && echo "маунт doc записан" && \
git log --oneline -1 && [ "$(git rev-parse HEAD)" = <SHA> ] && echo "коммит верный" && \
grep -q 'FIREBALL_FRAMES' mod/src/main/java/ua/zentix/airstrike/client/fx/layer/FxAtlas.java && \
test -f mod/src/main/resources/assets/airstrike/textures/fx/particle/fireball_15.png && echo "шар на месте"
```
Должно быть «маунт doc записан», «коммит верный» и «шар на месте». Нет — стоп, прислать вывод.

Сборка тестового jar (в фоне, тайм-аут вызова 25 мин; код не 0 — стоп, сообщить):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/fireball-SHA7" && cd "${W:?}/mod" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 20m ../tools/laptop_job.sh fireball-build -- ./gradlew scenarioJar -q --console=plain; echo "код $?"; ls -l build/scenario-libs/
```

## A. Прогон (один, без видео; в фоне, тайм-аут вызова 30 мин)
Площадка и пусковая — как в trails: площадка из гладкого камня на высоте 149 над городом (только в копии мира),
зритель в спектаторе пускает с `-272 150 -950`, на миг глядя на юг. Смотрит он с `-272 165 -950` на север
(`tp … 180 10`; на дальний удар — `180 3`). Цели на той же линии к северу: ракета `-272 64 -1100` (150 блоков),
шахед `-272 64 -1350` (400), ракета `-272 64 -2442` (1,5 км). `wait:blast` — шаги ждут взрыва на сервере (не дольше
3600 тиков); после него кадр `_3` — на 3-м тике (шар разгорается), `_23` — ещё через 20 (шар ракеты гаснет, 30 тиков;
шар шахеда уже погас, 13), `_100` — ракета вблизи через ~100 тиков (столб дыма). Потом ночь (`time set 18000`) — те же
три удара по соседним местам. Команда ждёт 40 тиков, кадр — 20.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/fireball-SHA7" && cd "${W:?}" && touch mod/run/fbl/.step-start && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
echo "начало: $(date -u +%T) UTC" && timeout -k 60 25m tools/laptop_job.sh fireball -- python3 tools/prod_client.py commands --world "Greenfield v0.5.4" --seconds 1400 \
  --size 1920x1080 \
  --prop 'airstrike.commands=hud:off;gamemode spectator;gamerule doDaylightCycle false;gamerule doWeatherCycle false;time set 6000;weather clear;tp @s -272 150 -950 180 6;fill -312 149 -990 -232 149 -930 minecraft:smooth_stone;tp @s -272 165 -950 180 10;wait:600;shot:base;tp @s -272 150 -950 0 6;airstrike missile at -272 64 -1100;tp @s -272 165 -950 180 10;wait:blast;wait:3;shot:day_near_3;shot:day_near_23;wait:40;shot:day_near_100;tp @s -272 150 -950 0 6;airstrike drone at -272 64 -1350;tp @s -272 165 -950 180 10;wait:blast;wait:3;shot:day_mid_3;shot:day_mid_23;tp @s -272 150 -950 0 6;airstrike missile at -272 64 -2442;tp @s -272 165 -950 180 3;wait:blast;wait:3;shot:day_far_3;shot:day_far_23;time set 18000;wait:200;shot:night_base;tp @s -272 150 -950 0 6;airstrike missile at -242 64 -1100;tp @s -272 165 -950 180 10;wait:blast;wait:3;shot:night_near_3;shot:night_near_23;wait:40;shot:night_near_100;tp @s -272 150 -950 0 6;airstrike drone at -302 64 -1350;tp @s -272 165 -950 180 10;wait:blast;wait:3;shot:night_mid_3;shot:night_mid_23;tp @s -272 150 -950 0 6;airstrike missile at -242 64 -2442;tp @s -272 165 -950 180 3;wait:blast;wait:3;shot:night_far_3;shot:night_far_23;wait:100'; \
code=$?; echo "код $code, конец: $(date -u +%T) UTC"; case "$code" in 124|137) systemctl --user stop 'airstrike-job-fireball-*';; esac; true
```
Наблюдатель: в `$W/mod/run/prod/logs/latest.log` идут строки `SCENARIO …`, в конце `SCENARIO done`.

## В. Лог, кадры, выжимка (сразу после прогона, одним вызовом)
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/fireball-SHA7" && cd "${W:?}" && D=mod/run/fbl && P=mod/run/prod && mkdir -p "$D/logs" "$D/frames" && \
G=$(find "$P/logs" -maxdepth 1 -name '*.log.gz' -newer "$D/.step-start" | sort -V) && \
{ for g in $G; do zcat "$g"; done; cat "$P/logs/latest.log"; } > "$D/logs/full.log" && cp -pn "$P/logs/gc.log" "$D/logs/" 2>/dev/null; \
for f in $(find "$P/screenshots" -maxdepth 1 -name '*.png' -newer "$D/.step-start" | sort); do n=$(basename "$f" .png); ffmpeg -loglevel error -y -i "$f" -q:v 3 "$D/frames/$n.jpg"; done; \
ls "$D/frames"; grep -a -c 'SCENARIO done' "$D/logs/full.log"; \
python3 tools/logscan.py "$D/logs/full.log" --all > "$D/logscan.txt"; wc -lc "$D/logscan.txt"; \
grep -aE 'SCENARIO (commands|fps|/airstrike|shot)|взрыва нет|Удар: |Слой эффектов|Шейдер|Текстура эффектов|Distant Horizons|has crashed|emergencySaveAndCrash|Unreported exception' "$D/logs/full.log" | cut -c1-300 > "$D/lines.txt"; wc -lc "$D/lines.txt"; \
grep -a 'SCENARIO far' "$D/logs/full.log" | grep -a 'ярче всех' | grep -aoE '^\[[^]]*\]|ярче всех: [^;]*|слой: квадратов [0-9]+, [0-9.]+ мс|процессор [0-9.]+' | paste -sd' ' | sed 's/ \[/\n[/g' > "$D/blasts.txt"; wc -lc "$D/blasts.txt"; \
grep -aE 'Pause (Young|Old|Full|Remark|Cleanup)' "$D/logs/gc.log" 2>/dev/null | awk '{ms=$NF; sub(/ms$/,"",ms); if (ms+0>5) print}' > "$D/gc-pauses.txt"; echo "пауз GC > 5 мс: $(wc -l < "$D/gc-pauses.txt")"; \
now=$(findmnt -n -o ID "/run/user/$(id -u)/doc"); [ -n "$now" ] && [ "$now" = "$(cat "$D/doc-mount.before")" ] && echo "маунт doc цел: $now" || { echo "ПРОВАЛ: маунт /run/user/$(id -u)/doc пропал или сменился (был $(cat "$D/doc-mount.before"), стал ${now:-нет})"; false; }
```

## Что прислать координатору
0. Вывод шагов 1, 2 и код и время прогона.
1. `mod/run/fbl/lines.txt` и `mod/run/fbl/blasts.txt` целиком (до 40 КБ).
2. logscan: ошибки, исключения, `Mixin`, `AccessTransformer`, GL (`GlDebug`, `OpenGL debug`) — или «ноль»;
   `gc-pauses.txt` (паузы > 5 мс; больше 60 строк — первые и последние 30).
3. Кадры — все JPEG из `mod/run/fbl/frames/` в `/mnt/project-files/fx-layer/fireball-laptop/` (SendUserFile), по
   строке на кадр.

## Проходит, если
- прогон кончается сам с **кодом 0**, `SCENARIO done` есть, нет `has crashed`/`emergencySaveAndCrash`/`Unreported exception`;
  все шесть `wait:blast` дождались взрыва (нет строк `взрыва нет`);
- маунт порталов flatpak `/run/user/<uid>/doc` цел: шаг В пишет «маунт doc цел» с тем же ID, что в шаге 2;
- строка «Слой эффектов: прогрев N мс» — один раз; строк «Шейдер … не загрузился», «Текстура эффектов … не найдена/не
  прочиталась» нет;
- logscan — без новых ошибок; исключений со `ua.zentix` в стеке нет;
- кадры днём: `day_near_3` — шар клубами, белёсо-жёлтое ядро и оранжево-красный край, **ни колец, ни ровных кругов**
  (ни белого, ни оранжевого кольца вокруг, ни кольца пыли по земле), экран не залит белым; `day_near_23` — шар
  догорает в чёрный дым, ровного оранжевого пятна нет; `day_near_100` — столб дыма, без колец;
  `day_mid_3`, `day_far_3` — маленький яркий шар с мягким бликом, блик круглый, **не срезан прямой** по рельефу,
  горизонту или LOD, светлого пятна цвета неба или тумана поверх шара нет; `_23` — шар погас или гаснет, дым;
- кадры ночью: `night_*_3` — вспышка освещает кадр и дым, зарево вокруг шара, ни колец, ни кругов; `night_*_23` —
  темнеет обратно, без светлого пятна, висящего на месте шара; `night_base` — ночь без следов прошлых вспышек;
- под шейдерами шар и дым не пропадают и не окрашены туманом шейдерпака.
