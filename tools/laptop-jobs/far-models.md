# Ноутбук: модели снарядов вдали под шейдерами со всей сборкой (одна короткая задача)

Тред «Дальние цели», ветка `claude/project-thread-y4jtvp`. Кандидат — один коммит: координатор вписывает его полный SHA
вместо `<SHA>` во **все** блоки ниже перед отправкой (и `SHA7` — первые 7 знаков). Артём в это время не играет.

**Что проверяем.** Снаряды дальше прорисовки теперь рисуются настоящей моделью (та же, что вблизи, в плитке атласа
своим шейдером `shaders/core/far_model`), а не тёмным кругом. Со сборкой Артёма (DH, Iris с Complementary, ~220 модов)
на копии Greenfield, без видео: сценарий `far-models` вешает зрителя в небе над городом (ноги `-272 220 -942`, взгляд на
север) и подаёт клиенту пути снарядов, как пакеты сервера (сущностей нет, удары не пускаются). Сначала три пары кадров
на одной позе — сущность и модель вдали (`match_drone_*` 60 блоков, `match_missile_*` 110, `match_b2_*` 120); потом ряд
всех семи снарядов (шахед, ракета, B-2, бомба, МБР на разгоне, РСЗО на разгоне, «Ланцет») на 300, 800 и 1500 блоках днём
и ночью (`far_day_*`, `far_night_*`) и в бинокль на 800, 1500 и 3000 (`far_scope_*`). Строки `SCENARIO far-models …` —
что нарисовано у каждого кадра (моделей, длина ближней модели в пикселях, полная или упрощённая), `SCENARIO far …` —
время дальней отрисовки за кадр.

## Правила (для каждого блока)
- Каждый блок — **одним вызовом, как написан**; состояние оболочки между вызовами не сохраняется. Блоки с шага 2
  начинаются с `W=… && cd "${W:?}"`.
- Папка — ровно `/mnt/data/projects/airstrike/mod/run/claude-work/far-models-SHA7` (новая). Уже есть — стоп, сообщить
  координатору, ничего не удалять и не перезаписывать.
- Инстанс Артёма, его миры и настройки не трогать: `prod_client.py` копирует инстанс и мир в `$W/mod/run/prod`.
- Клиент — только через `tools/nested_kwin.sh` (это делает `prod_client.py`); тяжёлое — только `tools/laptop_job.sh`;
  одна задача за раз.
- Остановить задачу — только `systemctl --user stop 'airstrike-job-far-models-*'`. Никаких `pkill`/`killall`/`kill`
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
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/far-models-SHA7" && echo "стоп: папка far-models-SHA7 уже есть"
MC="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/All of Create Aeronautics/minecraft"
ls "$MC/saves" | grep -x "Greenfield v0.5.4" || echo "стоп: нет мира Greenfield v0.5.4"
du -sh "$MC/saves/Greenfield v0.5.4" "$MC/mods"; df -h /mnt/data/projects/airstrike/mod/run
```
Свободно — не меньше Greenfield + mods + 5 ГБ.

## 2. Подготовка: worktree, сборка
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/far-models-SHA7" && R=/mnt/data/projects/airstrike && cd "${R:?}" && \
git fetch origin claude/project-thread-y4jtvp && git worktree add "$W" <SHA> && cd "${W:?}" && mkdir -p "$W/mod/run/far" && \
findmnt -n -o ID "/run/user/$(id -u)/doc" > "$W/mod/run/far/doc-mount.before" && echo "маунт doc записан" && \
git log --oneline -1 && [ "$(git rev-parse HEAD)" = <SHA> ] && echo "коммит верный" && \
grep -q 'planFarModels' mod/src/devtest/java/ua/zentix/airstrike/scenario/ClientScenario.java && \
test -f mod/src/main/resources/assets/airstrike/shaders/core/far_model.json && echo "сценарий на месте"
```
Должно быть «маунт doc записан», «коммит верный» и «сценарий на месте». Нет — стоп, прислать вывод.

```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/far-models-SHA7" && cd "${W:?}/mod" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 20m ../tools/laptop_job.sh far-models-build -- ./gradlew scenarioJar -q --console=plain; echo "код $?"; ls -l build/scenario-libs/
```

## A. Прогон (один, без видео; в фоне, тайм-аут вызова 25 мин)
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/far-models-SHA7" && cd "${W:?}" && touch mod/run/far/.step-start && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
echo "начало: $(date -u +%T) UTC" && timeout -k 60 20m tools/laptop_job.sh far-models -- python3 tools/prod_client.py far-models --world "Greenfield v0.5.4" --seconds 900 \
  --size 1920x1080 --prop 'airstrike.far.eye=-272,220,-942'; \
code=$?; echo "код $code, конец: $(date -u +%T) UTC"; case "$code" in 124|137) systemctl --user stop 'airstrike-job-far-models-*';; esac; true
```
Наблюдатель: в `$W/mod/run/prod/logs/latest.log` идут строки `SCENARIO …`, в конце `SCENARIO done`.

## В. Лог, кадры, выжимка (сразу после прогона, одним вызовом)
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/far-models-SHA7" && cd "${W:?}" && D=mod/run/far && P=mod/run/prod && mkdir -p "$D/logs" "$D/frames" && \
G=$(find "$P/logs" -maxdepth 1 -name '*.log.gz' -newer "$D/.step-start" | sort -V) && \
{ for g in $G; do zcat "$g"; done; cat "$P/logs/latest.log"; } > "$D/logs/full.log" && cp -pn "$P/logs/gc.log" "$D/logs/" 2>/dev/null; \
for f in $(find "$P/screenshots" -maxdepth 1 -name '*.png' -newer "$D/.step-start" | sort); do n=$(basename "$f" .png); ffmpeg -loglevel error -y -i "$f" -q:v 3 "$D/frames/$n.jpg"; done; \
ls "$D/frames"; grep -a -c 'SCENARIO done' "$D/logs/full.log"; \
python3 tools/logscan.py "$D/logs/full.log" --all > "$D/logscan.txt"; wc -lc "$D/logscan.txt"; \
grep -aE 'SCENARIO far-models|Дальняя картинка: прогрев|Шейдер дальних моделей|has crashed|emergencySaveAndCrash|Unreported exception' "$D/logs/full.log" | cut -c1-420 > "$D/lines.txt"; wc -lc "$D/lines.txt"; \
grep -a 'SCENARIO far ' "$D/logs/full.log" | grep -aoE '^\[[^]]*\]|моделей [0-9]+, [0-9.]+ мс \(самый долгий за секунду [^)]*\)' | paste -sd' ' | sed 's/ \[/\n[/g' > "$D/far-times.txt"; wc -lc "$D/far-times.txt"; \
awk '{for(i=1;i<=NF;i++){v=$(i+1); sub(/[,;)]$/,"",v); if($i=="мусора" && v+0>m) m=v+0; if($i=="процессор" && v+0>c) c=v+0}} END{print "наибольший кадр без сборки мусора: " m " мс, наибольшее время процессора в таком кадре: " c " мс"}' "$D/far-times.txt"; \
grep -aE 'SCENARIO fps' "$D/logs/full.log" | tail -n 30 | cut -c1-200 > "$D/fps.txt"; \
now=$(findmnt -n -o ID "/run/user/$(id -u)/doc"); [ -n "$now" ] && [ "$now" = "$(cat "$D/doc-mount.before")" ] && echo "маунт doc цел: $now" || { echo "ПРОВАЛ: маунт /run/user/$(id -u)/doc пропал или сменился (был $(cat "$D/doc-mount.before"), стал ${now:-нет})"; false; }
```

## Что прислать координатору
0. Вывод шагов 1, 2, код и время прогона.
1. `mod/run/far/lines.txt`, `far-times.txt` и `fps.txt` целиком (до 40 КБ), строку «наибольший кадр без сборки мусора …».
2. logscan: ошибки, исключения, `Mixin`, `AccessTransformer` — или «ноль».
3. Кадры — все JPEG из `mod/run/far/frames/` в `/mnt/project-files/design/far-models/laptop/` (SendUserFile), по строке на кадр.

## Проходит, если
- прогон кончается сам с **кодом 0**, `SCENARIO done` есть, нет `has crashed`/`emergencySaveAndCrash`/`Unreported exception`,
  нет строки «Шейдер дальних моделей не загрузился»;
- маунт порталов flatpak цел: шаг В пишет «маунт doc цел» с тем же ID, что в шаге 2; «ПРОВАЛ» — не прошло;
- строка «Дальняя картинка: прогрев N мс» есть — один раз, при входе в мир;
- в строках `SCENARIO far-models` у `match_*` есть «модель N px», у `far_day_300`, `far_night_300` и `far_scope_*`
  «моделей» в кадре — не меньше 4;
- дальняя отрисовка: «процессор» в кадре без сборки мусора — **не больше 2 мс** с моделями в кадре;
- logscan — без новых ошибок; исключений со `ua.zentix` в стеке нет;
- кадры: в парах `match_*` сущность и модель вдали — одна и та же модель в одной позе (вдали — без зубцов по краю),
  под шейдерами модель видна, не чёрная заплатка и не белая; в рядах на 300 и 800 блоках и в бинокле узнаются B-2
  (летающее крыло с пилой задней кромки), МБР с факелом, ракета и шахед — силуэтом; ночью — тёмные силуэты,
  факелы светят.
