# Ноутбук: дальняя видимость 2.4.1 — выбросы дальней отрисовки и залп РСЗО днём (одна короткая задача)

Тред «Оружие видно издалека», PR #175, ветка `claude/project-thread-o3dgvv`. Кандидат — один коммит: координатор
вписывает его полный SHA вместо `<SHA>` во **все** блоки ниже перед отправкой (и `SHA7` — первые 7 знаков).
Артём в это время не играет.

**Что проверяем.** Со сборкой Артёма (DH, Iris с шейдерами, ~220 модов) на копии Greenfield, без записи видео:
зритель в небе над городом; первый взрыв после входа — ракета за 1,5 км днём, потом залп РСЗО туда же днём, потом
ракета ночью. Дальняя картинка прогревается невидимым кадром при входе (строка «Дальняя картинка: прогрев N мс») —
первый взрыв не должен давать выброса. Строки `SCENARIO far` теперь делят самый долгий кадр за секунду: «без сборки
мусора» (кадры, в которые JVM не останавливалась на сборку, и время процессора потока в самом долгом из них) и «кадров
со сборкой» — пауза G1 останавливает все потоки, и кадр, попавший на неё, длинен не своей работой.

## Правила (для каждого блока)
- Каждый блок — **одним вызовом, как написан**; состояние оболочки между вызовами не сохраняется. Блоки с шага 2
  начинаются с `W=… && cd "${W:?}"`.
- Папка — ровно `/mnt/data/projects/airstrike/mod/run/claude-work/far-vis-SHA7` (новая). Уже есть — стоп, сообщить
  координатору, ничего не удалять и не перезаписывать.
- Инстанс Артёма, его миры и настройки не трогать: `prod_client.py` копирует инстанс и мир в `$W/mod/run/prod`.
- Клиент — только через `tools/nested_kwin.sh` (это делает `prod_client.py`); тяжёлое — только `tools/laptop_job.sh`;
  одна задача за раз.
- Остановить задачу — только `systemctl --user stop 'airstrike-job-far-vis-*'`. Никаких `pkill`/`killall`/`kill`
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
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/far-vis-SHA7" && echo "стоп: папка far-vis-SHA7 уже есть"
MC="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/All of Create Aeronautics/minecraft"
ls "$MC/saves" | grep -x "Greenfield v0.5.4" || echo "стоп: нет мира Greenfield v0.5.4"
du -sh "$MC/saves/Greenfield v0.5.4" "$MC/mods"; df -h /mnt/data/projects/airstrike/mod/run
```
Свободно — не меньше Greenfield + mods + 5 ГБ.

## 2. Подготовка: worktree, сборка
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/far-vis-SHA7" && R=/mnt/data/projects/airstrike && cd "${R:?}" && \
git fetch origin claude/project-thread-o3dgvv && git worktree add "$W" <SHA> && cd "${W:?}" && mkdir -p "$W/mod/run/far" && \
findmnt -n -o ID "/run/user/$(id -u)/doc" > "$W/mod/run/far/doc-mount.before" && echo "маунт doc записан" && \
git log --oneline -1 && [ "$(git rev-parse HEAD)" = <SHA> ] && echo "коммит верный" && \
grep -q 'без сборки мусора' mod/src/main/java/ua/zentix/airstrike/client/far/FarRenderer.java && echo "сценарий на месте"
```
Должно быть «маунт doc записан», «коммит верный» и «сценарий на месте». Нет — стоп, прислать вывод.

Сборка тестового jar (в фоне, тайм-аут вызова 25 мин; код не 0 — стоп, сообщить):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/far-vis-SHA7" && cd "${W:?}/mod" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 20m ../tools/laptop_job.sh far-vis-build -- ./gradlew scenarioJar -q --console=plain; echo "код $?"; ls -l build/scenario-libs/
```

## A. Прогон (один, без видео; в фоне, тайм-аут вызова 25 мин)
Зритель в спектаторе на `-272 140 -942` (город), взгляд на север; цель — `-272 64 -2442` (1,5 км). Перед пуском зритель
на миг поворачивается на курс 45° (`tp … 45 3`): старт — к северо-востоку от цели, вне города; после команды — снова
на север. День и погода стоят. `wait:blast` — шаги ждут следующего взрыва на сервере (не дольше 3600 тиков). Команда
ждёт 40 тиков, кадр — 20: `missile_flash` и `rszo_1` — на 2-м тике после первого взрыва, `rszo_2` — на 32-м,
`rszo_3` — на 82-м, `night_flash` — на 2-м тике после взрыва ночной ракеты.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/far-vis-SHA7" && cd "${W:?}" && touch mod/run/far/.step-start && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
echo "начало: $(date -u +%T) UTC" && timeout -k 60 20m tools/laptop_job.sh far-vis -- python3 tools/prod_client.py commands --world "Greenfield v0.5.4" --seconds 1000 \
  --size 1920x1080 \
  --prop 'airstrike.commands=hud:off;gamemode spectator;gamerule doDaylightCycle false;gamerule doWeatherCycle false;time set 6000;weather clear;tp @s -272 140 -942 180 3;wait:600;shot:base;tp @s -272 140 -942 45 3;airstrike missile at -272 64 -2442;tp @s -272 140 -942 180 3;wait:blast;wait:2;shot:missile_flash;wait:200;tp @s -272 140 -942 45 3;airstrike salvo rocket 30 20 at -272 64 -2442;tp @s -272 140 -942 180 3;wait:blast;wait:2;shot:rszo_1;wait:10;shot:rszo_2;wait:30;shot:rszo_3;wait:600;time set 18000;wait:100;tp @s -272 140 -942 45 3;airstrike missile at -272 64 -2442;tp @s -272 140 -942 180 3;wait:blast;wait:2;shot:night_flash;wait:400'; \
code=$?; echo "код $code, конец: $(date -u +%T) UTC"; case "$code" in 124|137) systemctl --user stop 'airstrike-job-far-vis-*';; esac; true
```
Наблюдатель: в `$W/mod/run/prod/logs/latest.log` идут строки `SCENARIO …`, в конце `SCENARIO done`.

## В. Лог, кадры, выжимка (сразу после прогона, одним вызовом)
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/far-vis-SHA7" && cd "${W:?}" && D=mod/run/far && P=mod/run/prod && mkdir -p "$D/logs" "$D/frames" && \
G=$(find "$P/logs" -maxdepth 1 -name '*.log.gz' -newer "$D/.step-start" | sort -V) && \
{ for g in $G; do zcat "$g"; done; cat "$P/logs/latest.log"; } > "$D/logs/full.log" && cp -pn "$P/logs/gc.log" "$D/logs/" 2>/dev/null; \
for f in $(find "$P/screenshots" -maxdepth 1 -name '*.png' -newer "$D/.step-start" | sort); do n=$(basename "$f" .png); ffmpeg -loglevel error -y -i "$f" -q:v 3 "$D/frames/$n.jpg"; done; \
ls "$D/frames"; grep -a -c 'SCENARIO done' "$D/logs/full.log"; \
python3 tools/logscan.py "$D/logs/full.log" --all > "$D/logscan.txt"; wc -lc "$D/logscan.txt"; \
grep -aE 'SCENARIO (commands|fps|/airstrike)|Удар: |Дальняя картинка: прогрев|has crashed|emergencySaveAndCrash|Unreported exception' "$D/logs/full.log" | cut -c1-300 > "$D/lines.txt"; wc -lc "$D/lines.txt"; \
grep -a 'SCENARIO far' "$D/logs/full.log" | grep -aoE '^\[[^]]*\]|[0-9.]+ мс \(самый долгий за секунду [^)]*\)|ярче всех: [^;]*' | paste -sd' ' | sed 's/ \[/\n[/g' > "$D/far-times.txt"; wc -lc "$D/far-times.txt"; \
awk '{for(i=1;i<=NF;i++){v=$(i+1); sub(/[,;)]$/,"",v); if($i=="мусора" && v+0>m) m=v+0; if($i=="процессор" && v+0>c) c=v+0}} END{print "наибольший кадр без сборки мусора: " m " мс, наибольшее время процессора в таком кадре: " c " мс"}' "$D/far-times.txt"; \
grep -aE 'Pause (Young|Old|Full|Remark|Cleanup)' "$D/logs/gc.log" 2>/dev/null | awk '{ms=$NF; sub(/ms$/,"",ms); if (ms+0>5) print}' > "$D/gc-pauses.txt"; echo "пауз GC > 5 мс: $(wc -l < "$D/gc-pauses.txt")"; \
now=$(findmnt -n -o ID "/run/user/$(id -u)/doc"); [ -n "$now" ] && [ "$now" = "$(cat "$D/doc-mount.before")" ] && echo "маунт doc цел: $now" || { echo "ПРОВАЛ: маунт /run/user/$(id -u)/doc пропал или сменился (был $(cat "$D/doc-mount.before"), стал ${now:-нет})"; false; }
```

## Что прислать координатору
0. Вывод шагов 1, 2 и код и время прогона.
1. `mod/run/far/lines.txt` и `mod/run/far/far-times.txt` целиком (до 40 КБ), строку «наибольший кадр без сборки мусора …».
2. logscan: ошибки, исключения, `Mixin`, `AccessTransformer` — или «ноль»; `gc-pauses.txt` (паузы > 5 мс; больше 60 строк —
   первые и последние 30).
3. Кадры — все JPEG из `mod/run/far/frames/` в `/mnt/project-files/far-visibility/laptop/` (SendUserFile), по строке на кадр.

## Проходит, если
- прогон кончается сам с **кодом 0**, `SCENARIO done` есть, нет `has crashed`/`emergencySaveAndCrash`/`Unreported exception`;
- маунт порталов flatpak `/run/user/<uid>/doc` цел: шаг В пишет «маунт doc цел» с тем же ID, что в шаге 2; «ПРОВАЛ» — не прошло;
- каждое `wait:blast` дождалось взрыва (нет строк `взрыва нет`);
- строка «Дальняя картинка: прогрев N мс» есть — один раз, при входе в мир, до первого пуска;
- дальняя отрисовка: «наибольший кадр без сборки мусора» — **не больше 2 мс** (в том числе в секунду первого взрыва);
  если больше — у этого кадра «процессор» не больше 2 мс (поток ждал, а не работал: его вытеснили или ждал драйвер;
  такие кадры перечислить со временем); секунды, где «самый долгий за секунду» больше 2 мс, а «без сборки мусора» —
  нет, — с «кадров со сборкой» ≥ 1, и в `gc-pauses.txt` есть пауза той же длины в ту же секунду (время gc.log —
  местное, лог игры — UTC+3);
- logscan — без новых ошибок; исключений со `ua.zentix` в стеке нет;
- кадры: `missile_flash` — белая вспышка с бликом у горизонта над целью; `rszo_1..3` (день, 1,5 км) — по расчёту
  в кадре в среднем меньше одной яркой точки: вспышка 122-мм ≈ 2 px с бликом ~6 px (0,1 с), шар ≈ 3–7 px (ярче неба
  ~0,2 с), 30 ракет за ~13 с; видны прежде всего столбы пыли и дыма; `night_flash` — белая вспышка с широким ореолом
  и заревом на облаках над местом; нет квадратных краёв у клубов, нет мигания.
