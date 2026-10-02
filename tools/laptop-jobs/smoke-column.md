# Ноутбук: столб дыма вблизи и вдали под шейдерами и с DH (одна короткая задача)

Тред «Дым столба вблизи», ветка `claude/project-thread-qdudh6`. Кандидат — один коммит: координатор вписывает его полный
SHA вместо `<SHA>` во **все** блоки ниже перед отправкой (и `SHA7` — первые 7 знаков). Артём в это время не играет.

**Что проверяем.** Столб дыма после взрыва теперь рисует дальняя картинка (`FarBlasts.column`) на любой дальности.
До этого ближе 0,8 прорисовки столба не было вовсе: там были только частицы, которые живут до 36 с, а столб стоит
3–4 минуты, — через полминуты после взрыва вблизи дым «рассеивался», а за краем прорисовки столб стоял снова. Сценарий
`fx-late` (клиент без окна, Sodium, Iris с шейдерпаком и DH из инстанса, как у Артёма): шахед и ракета крупным планом
(кадры 1–320 тиков после взрыва: шар, дым, столб проступает из шара), потом через 900 тиков (45 с) зритель по той же
линии на 0,5 и на 1,1 прорисовки: кадры `drone_late50_*`, `drone_late110_*`, `missile_late50_*`, `missile_late110_*`.
Строки `SCENARIO far … клубов N` — сколько клубов дальней картинки в кадре.

## Правила (для каждого блока)
- Каждый блок — **одним вызовом, как написан**; состояние оболочки между вызовами не сохраняется. Блоки с шага 2
  начинаются с `W=… && cd "${W:?}"`.
- Папка — ровно `/mnt/data/projects/airstrike/mod/run/claude-work/smoke-column-SHA7` (новая). Уже есть — стоп, сообщить
  координатору, ничего не удалять и не перезаписывать.
- Инстанс Артёма, его миры и настройки не трогать: сценарий создаёт свой мир в `$W/mod/run/scenario`, моды, шейдерпак
  и DH копируются оттуда задачами сборки.
- Клиент — только через `tools/nested_kwin.sh` (это делает `client_scenario.sh`); тяжёлое — только
  `tools/laptop_job.sh`; одна задача за раз.
- Остановить задачу — только `systemctl --user stop 'airstrike-job-smoke-column-*'`. Никаких `pkill`/`killall`/`kill`
  по имени, `./gradlew --stop`, `rm -rf`, `-f`/`--force`.
- Смерть прогона — координатору в течение 5 мин: последние 40 строк лога и `crash-reports/`. Прогон не повторять
  без слова координатора.
- Результат — только текст (до ~40 КБ на сообщение) и названные кадры (SendUserFile). Логи остаются в `$W`; уборку
  решает Артём словами в треде ноутбука.

## 1. Проверка перед запуском
Любой вывод у первых трёх команд, строка «стоп» — не запускать, сообщить координатору.
```sh
pgrep -a -x java | grep -Ei 'neoforge|minecraft'
pgrep -a -x prismlauncher
systemctl --user list-units 'airstrike-job-*' --state=active --no-legend
SHA=<SHA>; [ ${#SHA} = 40 ] || echo "стоп: SHA не вписан"
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/smoke-column-SHA7" && echo "стоп: папка smoke-column-SHA7 уже есть"
echo "маунт doc: $(findmnt -n -o ID "/run/user/$(id -u)/doc" || echo "стоп: маунта /run/user/$(id -u)/doc нет")"
MC="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/All of Create Aeronautics/minecraft"
sed -n 's/^shaderPack=//p' "$MC/config/iris.properties"; ls "$MC/mods" | grep -iE 'distanthorizons|iris|sodium'; df -h /mnt/data/projects/airstrike/mod/run
```

## 2. Подготовка: worktree
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/smoke-column-SHA7" && R=/mnt/data/projects/airstrike && cd "${R:?}" && \
git fetch origin claude/project-thread-qdudh6 && git worktree add "$W" <SHA> && cd "${W:?}" && \
findmnt -n -o ID "/run/user/$(id -u)/doc" > "$W/doc-mount.before" && echo "маунт doc записан" && \
git log --oneline -1 && [ "$(git rev-parse HEAD)" = <SHA> ] && echo "коммит верный" && \
grep -q 'fx-late' mod/src/devtest/java/ua/zentix/airstrike/scenario/ClientScenario.java && echo "сценарий на месте"
```
Должно быть «маунт doc записан», «коммит верный» и «сценарий на месте». Нет — стоп, прислать вывод.

## A. Прогон (один; в фоне, тайм-аут вызова 40 мин)
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/smoke-column-SHA7" && cd "${W:?}" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
echo "начало: $(date -u +%T) UTC" && timeout -k 60 35m tools/laptop_job.sh smoke-column -- tools/client_scenario.sh fx-late shaders dh; \
code=$?; echo "код $code, конец: $(date -u +%T) UTC"; case "$code" in 124|137) systemctl --user stop 'airstrike-job-smoke-column-*';; esac; true
```
Наблюдатель: в `$W/mod/run/scenario/logs/latest.log` идут строки `SCENARIO …`, в конце `SCENARIO done`.

## В. Лог, кадры, выжимка (сразу после прогона, одним вызовом)
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/smoke-column-SHA7" && cd "${W:?}" && S=mod/run/scenario && D=mod/run/smoke && mkdir -p "$D/frames" && \
for f in "$S"/screenshots/*.png; do n=$(basename "$f" .png); ffmpeg -loglevel error -y -i "$f" -q:v 3 "$D/frames/$n.jpg"; done; \
ls "$D/frames"; grep -a -c 'SCENARIO done' "$S/logs/latest.log"; \
python3 tools/logscan.py "$S/logs/latest.log" --all > "$D/logscan.txt"; wc -lc "$D/logscan.txt"; \
grep -aE 'SCENARIO (drone|missile)|SCENARIO far|has crashed|Unreported exception' "$S/logs/latest.log" | cut -c1-400 > "$D/lines.txt"; wc -lc "$D/lines.txt"; \
echo "маунт doc после: $(findmnt -n -o ID "/run/user/$(id -u)/doc")"; cat "$W/doc-mount.before"; \
busctl --user call org.kde.kglobalaccel /component/kwin org.kde.kglobalaccel.Component isActive
```

## Что прислать координатору
1. Вывод шагов 1, 2, код и время прогона, маунт doc до и после и `isActive` (должно быть `b true`).
2. Из `mod/run/smoke/lines.txt`: строки `SCENARIO … late…` (где стоял зритель) и по одной строке `SCENARIO far` сразу
   после каждого кадра `*_late*` (число клубов); logscan — ошибки и исключения со `ua.zentix` или «ноль».
3. Кадры (SendUserFile): `drone_late50_*`, `drone_late110_*`, `missile_late50_*`, `missile_late110_*` и из ранних —
   `drone_*` и `missile_*` на тиках +7, +35, +100, +240 после взрыва.

## Проходит, если
- прогон кончается сам с кодом 0, `SCENARIO done` есть, падений нет; маунт doc тот же, `isActive` — `true`;
- `*_late50_*`: над целью стоит столб дыма той же формы, что на `*_late110_*` того же снаряда (у ракеты — выше и шире);
  в строке `SCENARIO far` после кадра `*_late50_*` — «клубов» не меньше 10 у каждого снаряда (прежние столбы сценарий
  снимает перед следующим ударом; на main здесь 0: столба вблизи нет);
- ранние кадры: шар остывает в дым, столб проступает из него и поднимается; без квадратов, полос и резких обрезов
  клубов о землю; logscan — без новых ошибок мода.
