# Ноутбук: перемотка повтора Flashback — без живых взрывов и вспышек (одна задача, ~15 мин)

Тред «Flashback: взрывы при перемотке», ветка `claude/project-thread-kl5u8a` (PR #198). Кандидат — один коммит:
координатор вписывает его полный SHA вместо `<SHA>` во **все** блоки ниже перед отправкой (и `SHA7` — первые 7 знаков).
Артём в это время не играет.

**Что проверяем.** Сценарий `replay` в своей сборке (pack/, моды записи включены, `quicksave`) записывает игру:
шахед, ракета и ещё ядерка 1 кт в 700 блоках за спиной, — выходит, открывает повтор и перематывает. Сразу после открытия
повтор перематывается вперёд за все удары (`open`), потом проигрывается с места до первого пуска до конца (кадры
`replay_flight*`, `replay_gone*`), потом перемотки назад к концу (`back`), к началу проигрывания (`before`), на два тика
после подрыва (`after`) и снова вперёд (`forward`). На каждой — строка `SCENARIO replay seek …` и кадр
`replay_seek_<имя>.png`: ни одного живого события мода (счёт по видам — в строке), вспышки нет, живых подрывов нет,
подрыв до места — в своём возрасте по часам мира, до подрыва — его нет. Итог — `SCENARIO replay seeks ok` или
`… FAIL: <что>`. При проигрывании взрывы и подрыв должны быть живыми (это тоже в итоге).

## Правила (для каждого блока)
- Каждый блок — **одним вызовом, как написан**; состояние оболочки между вызовами не сохраняется. Блоки с шага 2
  начинаются с `W=… && cd "${W:?}"`.
- Папка — ровно `/mnt/data/projects/airstrike/mod/run/claude-work/replay-seek-SHA7` (новая). Уже есть — стоп, сообщить
  координатору, ничего не удалять и не перезаписывать.
- Инстанс Артёма, его миры и настройки не трогаются: каталог игры строит `tools/pack_dir.py` из pack/ в `$W`.
  Прежние папки `claude-work/*` только читаются (кэш модов копируется жёсткими ссылками).
- Клиент — только через `tools/nested_kwin.sh` (это делает `prod_client.py`); тяжёлое — только `tools/laptop_job.sh`;
  одна задача за раз.
- Остановить задачу — только `systemctl --user stop 'airstrike-job-replay-seek-*'`. Никаких `pkill`/`killall`/`kill`
  по имени, `./gradlew --stop`, `rm -rf`, `-f`/`--force`.
- Смерть запуска или «стоп: сценарий не начался» — координатору сразу: последние 40 строк лога и `crash-reports/`.
  Запуск не повторять без слова координатора.
- Результат — только текст (до ~40 КБ на сообщение) и названные кадры (SendUserFile). Логи и каталог остаются в `$W`;
  уборку решает Артём словами в треде «Ноутбук».

## 1. Проверка перед запуском
Любой вывод «стоп» — не запускать, сообщить координатору.
```sh
SHA=<SHA>; [ ${#SHA} = 40 ] || echo "стоп: SHA не вписан"
pgrep -x java >/dev/null && echo "стоп: работает java (игра Артёма или другая задача)"
systemctl --user list-units 'airstrike-job-*' --no-legend | grep . && echo "стоп: идёт другая задача"
echo "маунт doc: $(findmnt -n -o ID "/run/user/$(id -u)/doc" || echo "стоп: маунта /run/user/$(id -u)/doc нет")"
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/replay-seek-SHA7" && echo "стоп: папка replay-seek-SHA7 уже есть"
for c in pack-install-294398f pack-d-c8e7881; do d="/mnt/data/projects/airstrike/mod/run/claude-work/$c/mod/run/pack-cache"; [ -d "$d" ] && du -sh "$d"; done
df -h /mnt/data/projects/airstrike/mod/run
```
Свободно — не меньше 8 ГБ.

## 2. Подготовка: worktree, сборка мода, каталог игры
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/replay-seek-SHA7" && R=/mnt/data/projects/airstrike && cd "${R:?}" && \
git fetch origin claude/project-thread-kl5u8a && git worktree add "$W" <SHA> && cd "${W:?}" && mkdir -p "$W/mod/run/rs/out" && \
findmnt -n -o ID "/run/user/$(id -u)/doc" > "$W/mod/run/rs/doc-mount.before" && echo "маунт doc записан" && \
git log --oneline -1 && [ "$(git rev-parse HEAD)" = <SHA> ] && echo "коммит верный" && \
grep -q 'replay_seek_' mod/src/devtest/java/ua/zentix/airstrike/scenario/ReplayCheck.java && \
test -f mod/src/main/java/ua/zentix/airstrike/compat/FlashbackReplay.java && echo "сценарий на месте"
```
Должно быть «маунт doc записан», «коммит верный» и «сценарий на месте». Нет — стоп, прислать вывод.

```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/replay-seek-SHA7" && cd "${W:?}/mod" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 20m ../tools/laptop_job.sh replay-seek-build -- ./gradlew scenarioJar -q --console=plain; echo "код $?"; ls -l build/scenario-libs/
```

Каталог игры из pack/ с модами записи; кэш модов — жёсткими ссылками из прежней папки, если есть (недостающее
скачается); автозапись, автоконец и `quicksave` у Flashback:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/replay-seek-SHA7" && cd "${W:?}" && \
for c in pack-install-294398f pack-d-c8e7881; do d="/mnt/data/projects/airstrike/mod/run/claude-work/$c/mod/run/pack-cache"; \
  [ -d "$d" ] && [ ! -e mod/run/pack-cache ] && cp -al "$d" mod/run/pack-cache && echo "кэш из $c"; done; \
timeout 30m python3 tools/pack_dir.py mod/run/rs/B --optional && \
ls mod/run/rs/B/mods | grep -iE 'connector|flashback|forgified' && \
mkdir -p mod/run/rs/B/config/flashback && printf '%s\n' '{"configVersion": 2, "recordingControls": {"automaticallyStart": true, "automaticallyFinish": true, "quicksave": true}}' > mod/run/rs/B/config/flashback/flashback.json && \
cat mod/run/rs/B/config/flashback/flashback.json && echo "каталог готов: $(ls mod/run/rs/B/mods | grep -c '\.jar$') jar"
```
Должно кончиться «каталог готов», в списке — Flashback, Flashback NeoForge Fixed, Sinytra Connector, Forgified Fabric API.

## 3. Запуск (один, в фоне, тайм-аут вызова 25 мин)
Если через 180 с после «Game took» в логе нет ни одной строки `SCENARIO`, блок сам останавливает задачу.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/replay-seek-SHA7" && cd "${W:?}" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
D=mod/run/rs/B && O=mod/run/rs/out/run && L="$D/logs/latest.log" && \
if mkdir "$O" && test ! -e "$D/logs"; then \
  echo "начало: $(date -u +%T) UTC"; \
  { timeout -k 60 22m tools/laptop_job.sh replay-seek -- python3 tools/prod_client.py replay --no-copy --dir "$D" --seconds 1200; echo $? > "$O/code"; } & \
  sleep 20; WAITED=0; \
  for i in $(seq 240); do \
    [ -s "$O/code" ] && break; \
    if grep -aq 'Game took' "$L" 2>/dev/null && ! grep -aq 'SCENARIO' "$L"; then WAITED=$((WAITED + 5)); \
      [ "$WAITED" -ge 180 ] && { echo "стоп: сценарий не начался за 180 с после «Game took»"; systemctl --user stop 'airstrike-job-replay-seek-*'; break; }; fi; \
    grep -aq 'SCENARIO' "$L" 2>/dev/null && break; \
    sleep 5; done; \
  wait; echo "код $(cat "$O/code"), конец: $(date -u +%T) UTC"; case "$(cat "$O/code")" in 124|137) systemctl --user stop 'airstrike-job-replay-seek-*';; esac; \
  mv "$D/logs" "$O/logs" && for d in crash-reports screenshots; do [ -d "$D/$d" ] && mv "$D/$d" "$O/$d"; done; ls -l "$O"; ls "$O/logs"; \
else echo "стоп: запуск уже был ($O или $D/logs есть)"; fi
```
«стоп: запуск уже был» — ничего не запускалось, сообщить координатору. Ждём код 0 и `SCENARIO done`.

## 4. Выжимка и кадры (одним вызовом)
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/replay-seek-SHA7" && cd "${W:?}/mod/run/rs/out/run" && L=logs/latest.log && \
echo "код: $(cat code)"; grep -ac 'SCENARIO done' "$L"; \
grep -aE 'SCENARIO replay (saved|opened|play from|nuke at|played|seek|seeks|failed)|Flashback: (не читаются|метки)' "$L" | sed 's/^.*\]: //' | cut -c1-700; \
echo "== строки play (каждая пятая):"; grep -a 'SCENARIO replay play tick=' "$L" | sed 's/^.*\]: //' | awk 'NR%5==1' | cut -c1-300 | head -30; \
echo "== подрыв на сервере записи:"; grep -aE 'Ядерный|ядерн|NUKE|detonat' "$L" | grep -av SCENARIO | sed 's/^.*\]: //' | cut -c1-250 | head -8; \
echo "== ошибки мода и падения:"; grep -aE 'ua\.zentix.*(Exception|Error)|has crashed|emergencySaveAndCrash|Unreported exception|\[airstrike/ERROR\]' "$L" | cut -c1-300 | head -12; \
ls crash-reports 2>/dev/null; \
mkdir -p ../frames && for f in screenshots/replay_seek_*.png screenshots/replay_gone*_30.png; do [ -f "$f" ] && ffmpeg -loglevel error -y -i "$f" -q:v 3 "../frames/$(basename "$f" .png).jpg"; done; ls ../frames; \
now=$(findmnt -n -o ID "/run/user/$(id -u)/doc"); [ -n "$now" ] && [ "$now" = "$(cat ../../doc-mount.before)" ] && echo "маунт doc цел: $now" || echo "ПРОВАЛ: маунт /run/user/$(id -u)/doc пропал или сменился"
```
Прислать координатору весь вывод и кадры `replay_seek_open`, `replay_seek_back`, `replay_seek_before`,
`replay_seek_after`, `replay_seek_forward` и два `replay_gone*_30` (JPEG из `$W/mod/run/rs/out/frames`).

**Решение.** Годен: код 0, `SCENARIO done`, пять строк `SCENARIO replay seek … ok`, `SCENARIO replay nuke at … live=true`,
`SCENARIO replay seeks ok`, ошибок мода и падений нет. Иначе — что не так, по строкам.
