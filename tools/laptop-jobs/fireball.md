# Ноутбук: огненный шар и вспышка взрывов днём и ночью (одна задача)

Тред «Вспышки и дым», шаг 3. Кандидат — один коммит: координатор вписывает его полный SHA вместо `<SHA>` во **все**
блоки ниже перед отправкой (и `SHA7` — первые 7 знаков). Артём в это время не играет.

**Что проверяем.** Жалоба Артёма: вспышки взрывов «топорные, особенно днём… оранжевые кольца». Теперь огненный шар —
анимация из 16 кадров (цвет по температуре, размер и время шара — из той же таблицы, что дальняя картинка), белого
ударного кольца и кольца пыли больше нет, дым вокруг шара светится, только пока светит шар. Днём — яркое ядро без
засветки экрана, ночью — вспышка на экране и зарево. Блик вдали рисуется «в глазу» и не режется рельефом или LOD.
Со сборкой Артёма (DH, Iris с шейдерами, ~220 модов) на копии Greenfield, без видео: площадка из fx-layer, по пять
ударов днём и ночью — ракета в ~90 блоках на своей площадке над городом (частицы, ближе 0,8 прорисовки), ракета за
краем площадки, шахед в 400 и ракета в 1,5 км (только дальняя картинка: частицы дальше 0,8 прорисовки гаснут сами,
туман Minecraft с DH их не гасит). Строки `SCENARIO far` раз в секунду: `ярче всех: ядро … px, пересвет …, блик … px,
вуаль … px` и время слоя.

Второй прогон. В первом (4ad82a2) дым столба вдали проступал с первого тика и за 8 тиков закрывал шар (днём бурый ком,
ночью кольцо зарева с чёрной серединой), частицы взрыва в 400 блоках рисовались вместе с дальней картинкой, ночное
зарево из-за гребня ложилось на дома и LOD у места удара точками и полосами, а ближний шар был за краем площадки.
Теперь дым проступает, пока шар тает, частицы уступают дальней картинке сами, зарево стоит перед местом удара и
закрывается тем, что ближе, плавно; ближние удары снимаются с края площадки сверху.

Третий прогон. Во втором (d1d7fd5) вдали и за краем площадки всё прошло, но ближний шар днём был за домом (луч к шару
закрыт: строки «ярче всех» нет, хвосты осколков выходят из-за дома), ночью — за высоткой, шахед в 400 закрыл дым
ближних ударов на той же линии, а зарево из-за края ночью заливало кремовым пол-кадра. Теперь зарево закрытого шара не
ярче вуали открытого и есть вблизи (за высоткой — тоже), кадр огненного шара стоит у его передней половины (плоскость
через середину сверху уходила низом шара в землю), ближние удары — по своей площадке на высоте 170 к северо-западу
(сбоку и сверху, ничем не закрыты), удар за краем площадки — к востоку, на линии шахеда и дальней ракеты других ударов нет.

Четвёртый прогон. В третьем (95f3c29) ближний шар днём и сверху виден целиком, вдали и за краем всё прошло, но у
высотки ночью виден только жёлтый край шара у угла, без блика и зарева, ночью клубы пыли у шара лиловые, а полоса пыли
под ним тёмно-синяя, и вспышка днём — ровный лимонно-жёлтый шар. Причины: блик шёл по двум лучам к оси шара (у угла оба
закрыты — блика нет, хотя полшара видно), пыль у шара освещала только луна (синим, и поверх жёлтого шара она лиловая),
у кадров шара в 2000 K синий канал был нулём, и яркое оставалось чисто жёлтым. Теперь блик — по доле открытого диска
(7 лучей), шар освещает дым, пыль и шлейфы вокруг (свет в шейдере слоя), яркое уходит к белому. Сверху днём кадр — на
9-м тике (оранжевый шар), а не на 3-м (вспышка).

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
grep -q 'smokeFrom' mod/src/main/java/ua/zentix/airstrike/client/far/FarBlasts.java && \
grep -q 'static double behind' mod/src/main/java/ua/zentix/airstrike/client/far/FarBlasts.java && \
grep -q 'BALL_FRONT' mod/src/main/java/ua/zentix/airstrike/client/far/FarSprites.java && \
grep -q 'RING_RAYS' mod/src/main/java/ua/zentix/airstrike/client/far/FarBlasts.java && \
grep -q 'FxBallLight' mod/src/main/resources/assets/airstrike/shaders/core/fx.vsh && \
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
зритель в спектаторе пускает с `-272 150 -950`, на миг глядя на юг. Для ближних ударов — вторая площадка на высоте 170
к северо-западу (`-400..-340`, `-1090..-1030`, тоже только в копии мира): днём ракета в `-355 171 -1070`, зритель сбоку
с `-290 190 -1005` (`135 12`, ~95 блоков, `_near`), потом в `-385 171 -1070`, зритель сверху с `-385 240 -1020`
(`180 54`, ~85 блоков, `_top`, кадры на 9-м и 29-м тике); ночью — в `-355 171 -1045` сбоку с `-290 190 -980`. Ночью ещё ракета в `-242 64 -1100`
среди высоток, зритель сверху с `-272 190 -1040` (`207 48`, `_tower`): шар за высоткой, видно зарево. За краем
площадки (`_hidden`) — ракета в `-192 64 -1100` днём и в `-212 64 -1100` ночью, зритель с `-272 165 -950` на север
(`180 10`). Шахед `-272 64 -1350` днём и `-302 64 -1350` ночью (400 блоков) и ракета `-272`/`-242 64 -2442` (1,5 км) —
с `-272 165 -950` (`180 10`, на дальний удар — `180 3`); других ударов на этой линии нет. `wait:blast` — шаги ждут
взрыва на сервере (не дольше 3600 тиков); после него кадр `_3` — на 3-м тике (шар разгорается), `_23` — ещё через 20
(шар ракеты гаснет, 30 тиков; шар шахеда уже погас, 13), `_100` — ракета сбоку через ~100 тиков (столб дыма). Команда
ждёт 40 тиков, кадр — 20.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/fireball-SHA7" && cd "${W:?}" && touch mod/run/fbl/.step-start && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
echo "начало: $(date -u +%T) UTC" && timeout -k 60 25m tools/laptop_job.sh fireball -- python3 tools/prod_client.py commands --world "Greenfield v0.5.4" --seconds 1400 \
  --size 1920x1080 \
  --prop 'airstrike.commands=hud:off;gamemode spectator;gamerule doDaylightCycle false;gamerule doWeatherCycle false;time set 6000;weather clear;tp @s -272 150 -950 180 6;fill -312 149 -990 -232 149 -930 minecraft:smooth_stone;tp @s -272 165 -950 180 10;wait:600;fill -400 170 -1090 -340 170 -1030 minecraft:smooth_stone;wait:20;shot:base;tp @s -272 150 -950 0 6;airstrike missile at -355 171 -1070;tp @s -290 190 -1005 135 12;wait:blast;wait:3;shot:day_near_3;shot:day_near_23;wait:40;shot:day_near_100;tp @s -272 150 -950 0 6;airstrike missile at -385 171 -1070;tp @s -385 240 -1020 180 54;wait:blast;wait:9;shot:day_top_9;shot:day_top_29;tp @s -272 150 -950 0 6;airstrike missile at -192 64 -1100;tp @s -272 165 -950 180 10;wait:blast;wait:3;shot:day_hidden_3;tp @s -272 150 -950 0 6;airstrike drone at -272 64 -1350;tp @s -272 165 -950 180 10;wait:blast;wait:3;shot:day_mid_3;shot:day_mid_23;tp @s -272 150 -950 0 6;airstrike missile at -272 64 -2442;tp @s -272 165 -950 180 3;wait:blast;wait:3;shot:day_far_3;shot:day_far_23;time set 18000;wait:200;shot:night_base;tp @s -272 150 -950 0 6;airstrike missile at -355 171 -1045;tp @s -290 190 -980 135 12;wait:blast;wait:3;shot:night_near_3;shot:night_near_23;wait:40;shot:night_near_100;tp @s -272 150 -950 0 6;airstrike missile at -242 64 -1100;tp @s -272 190 -1040 207 48;wait:blast;wait:3;shot:night_tower_3;shot:night_tower_23;tp @s -272 150 -950 0 6;airstrike missile at -212 64 -1100;tp @s -272 165 -950 180 10;wait:blast;wait:3;shot:night_hidden_3;tp @s -272 150 -950 0 6;airstrike drone at -302 64 -1350;tp @s -272 165 -950 180 10;wait:blast;wait:3;shot:night_mid_3;shot:night_mid_23;tp @s -272 150 -950 0 6;airstrike missile at -242 64 -2442;tp @s -272 165 -950 180 3;wait:blast;wait:3;shot:night_far_3;shot:night_far_23;wait:100'; \
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
  все десять `wait:blast` дождались взрыва (нет строк `взрыва нет`);
- маунт порталов flatpak `/run/user/<uid>/doc` цел: шаг В пишет «маунт doc цел» с тем же ID, что в шаге 2;
- строка «Слой эффектов: прогрев N мс» — один раз; строк «Шейдер … не загрузился», «Текстура эффектов … не найдена/не
  прочиталась» нет;
- logscan — без новых ошибок; исключений со `ua.zentix` в стеке нет;
- кадры днём: `day_near_3` (сбоку, вспышка) — шар на площадке **виден целиком**, белёсо-кремовый с жёлтым, **не ровный
  лимонно-жёлтый**; `day_top_9` (сверху) — купол целиком, низ не срезан площадкой, оранжевый с белёсо-жёлтым ядром
  и оранжево-красным краем; оба — клубами, **ни колец, ни ровных кругов** (ни белого, ни оранжевого кольца вокруг,
  ни кольца пыли по земле), бурых клубов поверх шара нет, экран не залит белым; пыль и дым у шара не серо-синие, а
  тёплые от его света; `day_near_23`, `day_top_29` — шар догорает в чёрный дым, ровного оранжевого пятна нет; `day_near_100` — столб дыма,
  без колец; `day_hidden_3` — место за краем площадки: над краем самое большее слабое зарево, **белого пятна нет**;
  `day_mid_3`, `day_far_3` — маленький **яркий оранжево-жёлтый шар** (не бурый ком) с мягким бликом, блик круглый,
  **не срезан прямой** по рельефу, горизонту или LOD, светлого пятна цвета неба или тумана поверх шара нет, белых точек
  искр вокруг нет, дыма других ударов на месте нет; `_23` — шар погас или гаснет, дым;
- кадры ночью: `night_near_3`, `night_mid_3`, `night_far_3` — шар и зарево вокруг него, ни колец, ни кругов, **тёмной
  середины в зареве нет**; у `night_near_3` клубы пыли у шара и полоса пыли под ним **освещены шаром — тёплые, не
  лиловые и не тёмно-синие**; `night_tower_3` — видимая у угла высотки часть шара **с бликом**: вокруг тёплый свет,
  кадр не тёмный, дома не в точках и полосах; `night_hidden_3` — зарево над краем площадки **не шире и не ярче блика открытого шара** (`night_near_3`,
  `night_mid_3`), не заливает кремовым пол-кадра, дома и LOD за краем без точек и полос; `night_*_23` — темнеет
  обратно, без светлого пятна, висящего на месте шара; `night_base` — ночь без следов прошлых вспышек; у места дальнего
  дневного удара (1,5 км, кадр через ~290 тиков после него) может догорать воронка — оранжевая точка с ореолом, она
  гаснет за 600 тиков после взрыва (`burnTicks` ракеты);
- под шейдерами шар и дым не пропадают и не окрашены туманом шейдерпака.
