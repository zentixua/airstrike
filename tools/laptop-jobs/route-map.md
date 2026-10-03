# Ноутбук: маршрут на карте наведения со всей сборкой (одна короткая задача)

Тред «Маршрут на карте», ветка `claude/flight-route-7fc19k`, PR #213. Кандидат — один коммит: координатор вписывает его
полный SHA вместо `<SHA>` во **все** блоки ниже перед отправкой (и `SHA7` — первые 7 знаков). Артём в это время не играет.

**Что проверяем.** На карте наведения появился маршрут оператора: Shift+ЛКМ ставит точку, точку тянут мышью, ПКМ
убирает, линия от игрока через точки к цели, под картой длина и время полёта; длиннее дальности оружия — огня нет;
шахед, ракета и «Ланцет» летят по точкам. Сценарий клиента `route-map` (без окна, во вложенном KWin) делает всё это
вводом экрана на копии мира со всей сборкой Артёма (DH, Iris с шейдерпаком): пульт с шахедом, карта отдалена, цель
кликом, три точки, третья убрана ПКМ, вторая перетащена, две лишние дальние точки — огонь не даётся, их убрали — огонь
по Enter, потом полёт по точкам до попадания. Строки `SCENARIO route-map …` — каждый шаг с итогом OK или FAIL, кадры
`route-map_*` — карта с маршрутом и снаряд в полёте.

## Правила (для каждого блока)
- Каждый блок — **одним вызовом, как написан**; состояние оболочки между вызовами не сохраняется. Блоки с шага 2
  начинаются с `W=… && cd "${W:?}"`.
- Папка — ровно `/mnt/data/projects/airstrike/mod/run/claude-work/route-map-SHA7` (новая). Уже есть — стоп, сообщить
  координатору, ничего не удалять и не перезаписывать.
- Инстанс Артёма, его миры и настройки не трогать: `prod_client.py` копирует инстанс и мир в `$W/mod/run/prod`.
- Клиент — только через `tools/nested_kwin.sh` (это делает `prod_client.py`); тяжёлое — только `tools/laptop_job.sh`;
  одна задача за раз.
- Остановить задачу — только `systemctl --user stop 'airstrike-job-route-map-*'`. Никаких `pkill`/`killall`/`kill`
  по имени, `./gradlew --stop`, `rm -rf`, `-f`/`--force`.
- Смерть прогона — координатору в течение 5 мин: последние 40 строк лога и `crash-reports/`. Прогон не повторять
  без слова координатора.
- Результат — только текст (до ~40 КБ на сообщение) и названные кадры (SendUserFile). Логи и копия мира остаются
  в `$W`; уборку решает Артём словами в треде «Ноутбук».

## 1. Проверка перед запуском
Любой вывод «стоп» — не запускать, сообщить координатору.
```sh
SHA=<SHA>; [ ${#SHA} = 40 ] || echo "стоп: SHA не вписан"
pgrep -x java >/dev/null && echo "стоп: работает java (игра Артёма или другая задача)"
systemctl --user list-units 'airstrike-job-*' --no-legend | grep . && echo "стоп: идёт другая задача"
echo "маунт doc: $(findmnt -n -o ID "/run/user/$(id -u)/doc" || echo "стоп: маунта /run/user/$(id -u)/doc нет")"
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/route-map-SHA7" && echo "стоп: папка route-map-SHA7 уже есть"
MC="$(python3 /mnt/data/projects/airstrike/tools/paths.py MC)"; echo "инстанс: $MC"
ls "$MC/saves" | grep -x "Greenfield v0.5.4" || { echo "стоп: нет мира Greenfield v0.5.4, миры:"; ls "$MC/saves"; }
du -sh "$MC/saves/Greenfield v0.5.4" "$MC/mods"; df -h /mnt/data/projects/airstrike/mod/run
```
Свободно — не меньше мира + mods + 5 ГБ.

## 2. Подготовка: worktree, сборка
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/route-map-SHA7" && R=/mnt/data/projects/airstrike && cd "${R:?}" && \
git fetch origin claude/flight-route-7fc19k && git worktree add "$W" <SHA> && cd "${W:?}" && mkdir -p "$W/mod/run/route" && \
findmnt -n -o ID "/run/user/$(id -u)/doc" > "$W/mod/run/route/doc-mount.before" && echo "маунт doc записан" && \
git log --oneline -1 && [ "$(git rev-parse HEAD)" = <SHA> ] && echo "коммит верный" && \
grep -q 'planRouteMap' mod/src/devtest/java/ua/zentix/airstrike/scenario/ClientScenario.java && echo "сценарий на месте"
```
Должно быть «маунт doc записан», «коммит верный» и «сценарий на месте». Нет — стоп, прислать вывод.

```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/route-map-SHA7" && cd "${W:?}/mod" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 20m ../tools/laptop_job.sh route-map-build -- ./gradlew scenarioJar -q --console=plain; echo "код $?"; ls -l build/scenario-libs/
```

## A. Прогон (один, без видео; в фоне, тайм-аут вызова 25 мин)
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/route-map-SHA7" && cd "${W:?}" && touch mod/run/route/.step-start && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
echo "начало: $(date -u +%T) UTC" && timeout -k 60 20m tools/laptop_job.sh route-map -- python3 tools/prod_client.py route-map --world "Greenfield v0.5.4" --seconds 900 \
  --size 1920x1080; \
code=$?; echo "код $code, конец: $(date -u +%T) UTC"; case "$code" in 124|137) systemctl --user stop 'airstrike-job-route-map-*';; esac; true
```
Наблюдатель: в `$W/mod/run/prod/logs/latest.log` идут строки `SCENARIO route-map …`, в конце `SCENARIO done`.

## В. Лог, кадры, выжимка (сразу после прогона, одним вызовом)
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/route-map-SHA7" && cd "${W:?}" && D=mod/run/route && P=mod/run/prod && mkdir -p "$D/logs" "$D/frames" && \
G=$(find "$P/logs" -maxdepth 1 -name '*.log.gz' -newer "$D/.step-start" | sort -V) && \
{ for g in $G; do zcat "$g"; done; cat "$P/logs/latest.log"; } > "$D/logs/full.log"; \
for f in $(find "$P/screenshots" -maxdepth 1 -name 'route-map_*.png' -newer "$D/.step-start" | sort); do n=$(basename "$f" .png); ffmpeg -loglevel error -y -i "$f" -q:v 3 "$D/frames/$n.jpg"; done; \
ls "$D/frames"; grep -a -c 'SCENARIO done' "$D/logs/full.log"; \
python3 tools/logscan.py "$D/logs/full.log" --all > "$D/logscan.txt"; wc -lc "$D/logscan.txt"; \
grep -aE 'SCENARIO route-map|SCENARIO flights|Удар: |has crashed|emergencySaveAndCrash|Unreported exception' "$D/logs/full.log" | cut -c1-420 > "$D/lines.txt"; wc -lc "$D/lines.txt"; \
now=$(findmnt -n -o ID "/run/user/$(id -u)/doc"); [ -n "$now" ] && [ "$now" = "$(cat "$D/doc-mount.before")" ] && echo "маунт doc цел: $now" || { echo "ПРОВАЛ: маунт /run/user/$(id -u)/doc пропал или сменился (был $(cat "$D/doc-mount.before"), стал ${now:-нет})"; false; }
```

## Что прислать координатору
0. Вывод шагов 1, 2, код и время прогона.
1. `mod/run/route/lines.txt` целиком (до 40 КБ).
2. logscan: ошибки, исключения, `Mixin`, `AccessTransformer` — или «ноль».
3. Кадры — все JPEG из `mod/run/route/frames/` в `/mnt/project-files/design/route-map/laptop/` (SendUserFile), по строке на кадр.

## Проходит, если
- прогон кончается сам с **кодом 0**, `SCENARIO done` есть, нет `has crashed`/`emergencySaveAndCrash`/`Unreported exception`;
- маунт порталов flatpak цел: шаг В пишет «маунт doc цел» с тем же ID, что в шаге 2;
- строки `SCENARIO route-map edit …`, `… too long …` и `… impact …` — с **OK**, ни одной с FAIL; у `impact` — «точек
  пройдено 2 из 2»; строка `Удар: drone ×1 …` содержит «маршрут» с двумя точками;
- logscan — без новых ошибок; исключений со `ua.zentix` в стеке нет;
- кадры: на карте пронумерованные точки 1 и 2, зелёная линия от «вы» через них к перекрестию цели, под картой строка
  «Маршрут: точек 2 из 5 · … км · полёт ≈ …»; на кадре «длиннее дальности» линия красная и строка о дальности; кнопки
  «Огонь», «Сбросить маршрут», «Ко мне», «Пульт» не налезают друг на друга и на строки.
