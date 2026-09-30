# Ноутбук: финальная проверка кандидата перед выпуском (одна, полная сборка), версия 2

Из заданий потоков PR: `perf/impact-profile-job.md` (#146), `nuke/laptop-gate.md` (#138), `map/laptop-map-check.md`
(#150), `drones/laptop-drones.md` (#148), `crater/laptop-crater.md` (#147), `missile/laptop-visible-missile.md` (#149),
`target/laptop-far-target.md` (#145). План — `arch/foundation-plan.md`, раздел 2. Здесь всё для прогона: те файлы
ноутбуку не нужны (он не читает /mnt/project-files).

**Когда.** Все пять корней и семь PR в `main`, CI зелёный ± Lithium. Кандидат — один коммит `main`: координатор
вписывает его полный SHA вместо `<SHA>` во **все** блоки ниже перед отправкой (и строку `SHA7` — первые 7 знаков).
Ноутбук проверяет его сам (шаг 1). Артём в это время не играет.

**Что проверяем** (всё — на копиях мира, одной задачей за раз, по порядку):

| Шаг | Что | Мир | Задача (`laptop_job.sh`) |
|---|---|---|---|
| A | Залп: ракета и 30 шахедов, время тика в окне удара (строки `second`) | Greenfield | `final-salvo` |
| A2 | Взрыв рядом с аппаратом Sable: цена ванильной единицы (замер) | Greenfield | `final-craft` |
| B | Шахеды после потери цели | Greenfield | `final-drones` |
| C | Видимость ракеты на подлёте | Greenfield | `final-missile` |
| D | Воронки: осыпание песка и гравия | Greenfield | `final-crater` |
| E | Карта наведения с DH, залп с карты | Greenfield | `final-map` |
| F | Дальняя цель по карте: ракета и РСЗО по крыше, ракета в Newisle | Greenfield, Newisle | `final-far1`, `final-far2`, `final-far3` |
| G | Ядерка 15 кт в воздухе: руины, кадры, куча | Greenfield | `final-nuke` |

## Правила (для каждого блока)
- Каждый блок — **одним вызовом, как написан**; состояние оболочки между вызовами не сохраняется. Блоки с шага 2
  начинаются с `W=… && cd "${W:?}"`.
- Папка — ровно `/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7` (новая). Уже есть — стоп, сообщить
  координатору, ничего не удалять и не перезаписывать.
- Инстанс Артёма, его миры и настройки не трогать: `prod_client.py` копирует инстанс и мир в `$W/mod/run/prod`
  (эту копию каждый шаг делает заново — поэтому **выжимку шага снимать сразу после него**, до следующего шага).
- Клиент — только через `tools/nested_kwin.sh` (это делает `prod_client.py`); тяжёлое — только `tools/laptop_job.sh`;
  одна задача за раз.
- Остановить задачу — только `systemctl --user stop 'airstrike-job-final-<шаг>-*'`. Никаких `pkill`/`killall`/`kill`
  по имени, `./gradlew --stop`, `rm -rf`, `-f`/`--force`.
- Смерть прогона — координатору в течение 5 мин: последние 40 строк лога и `crash-reports/`. Прогон не повторять
  без слова координатора.
- Результат — только текст (до ~40 КБ на сообщение; больше — несколькими, не урезая) и названные кадры
  (SendUserFile). Логи, JFR и копии миров остаются в `$W` (в .gitignore); уборку решает Артём словами в треде
  «Ноутбук».

## 1. Проверка перед запуском
Любой вывод у первых трёх команд, строка «стоп»/«нет» — не запускать, сообщить координатору.
```sh
pgrep -a -x java | grep -Ei 'neoforge|minecraft'
pgrep -a -x prismlauncher
systemctl --user list-units 'airstrike-job-*' --state=active --no-legend
SHA=<SHA>; [ ${#SHA} = 40 ] || echo "стоп: SHA не вписан"
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && echo "стоп: папка final-SHA7 уже есть"
MC="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/All of Create Aeronautics/minecraft"
ls "$MC/saves"        # есть папки «Greenfield v0.5.4» и «Newisle v1.3.1»
du -sh "$MC/saves/Greenfield v0.5.4" "$MC/saves/Newisle v1.3.1" "$MC/mods"; df -h /mnt/data/projects/airstrike/mod/run
C="$MC/saves/Greenfield v0.5.4/serverconfig/airstrike-server.toml"; [ -e "$C" ] || C="$MC/config/airstrike-server.toml"; echo "конфиг: $C"
grep -hE '^\s*(bomber_flight_time|flight_time|destruction_ms_per_tick)\s*=' "$C" || echo "строк нет — значения по умолчанию"
```
Свободно — не меньше Greenfield + mods + 5 ГБ (копия одна, её перезаписывает каждый шаг). Прислать координатору
последнюю строку: `bomber_flight_time` (F, по умолчанию 40) и `flight_time` (N, тиков, по умолчанию 1800). F > 120
или N > 3600 — не запускать, сообщить.

## 2. Подготовка: worktree, сборка, сценарии на месте
```sh
cd /mnt/data/projects/airstrike && git fetch origin main && \
git worktree add "/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" <SHA> && \
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p "$W/mod/run/final" && \
git log --oneline -1 && [ "$(git rev-parse HEAD)" = <SHA> ] && echo "коммит верный" && \
grep -hoE '"(strike-profile|commands|fx|salvo-map|target-map)"' mod/src/devtest/java/ua/zentix/airstrike/scenario/ClientScenario.java | sort -u
```
Должно быть «коммит верный» и пять имён сценариев: `commands`, `fx`, `salvo-map`, `strike-profile`, `target-map`.
Не хватает — стоп, прислать вывод.

Сборка тестового jar (в фоне, тайм-аут вызова 25 мин; код не 0 — стоп, сообщить):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}/mod" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 20m ../tools/laptop_job.sh final-build -- ./gradlew scenarioJar -q --console=plain; echo "код $?"; ls -l build/scenario-libs/
```
(`prod_client.py` в каждом шаге ещё раз зовёт `scenarioJar` — после этого это секунды.)

## 3. Как идёт каждый шаг (A, A2, B–G)
1. Прогон — сразу в фоне, тайм-аут вызова указан у шага.
2. Наблюдатель: в `$W/mod/run/prod/logs/latest.log` идут строки `SCENARIO …`, в конце `SCENARIO done`.
3. Сразу после конца — **лог и кадры шага** (блок ниже; `S` — папка шага: `A`, `A2`, `B`, `C`, `D`, `E`, `F1`, `F2`,
   `F3`, `G`), потом выжимка шага в `$W/mod/run/final/<шаг>/` (блок у шага; она читает сохранённые лог и кадры), потом
   следующий шаг. `prod_client.py` следующего шага удаляет `prod/logs` и `prod/screenshots` целиком, а log4j в полночь
   по часам лога (UTC+3) убирает `latest.log` в архив `logs/<дата>-N.log.gz`: без этого блока лог и кадры шага теряются
   (ранний прогон 30.09 потерял лог A2 и кадры D).

Лог и кадры шага (одним вызовом сразу после прогона, `S=` — папка этого шага; `full.log` — архивы ротации новее начала
шага по порядку, затем `latest.log`; `full.log` уже есть — стоп, сообщить координатору, ничего не перезаписывать):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && S=A && D="mod/run/final/$S" && P=mod/run/prod && \
test ! -e "$D/logs/full.log" && mkdir -p "$D/logs" "$D/screenshots" && \
G=$(find "$P/logs" -maxdepth 1 -name '*.log.gz' -newer mod/run/final/.step-start | sort -V) && \
{ for g in $G; do zcat "$g"; done; cat "$P/logs/latest.log"; } > "$D/logs/full.log" && \
for f in $G "$P/logs/latest.log" "$P/logs/gc.log"; do [ -e "$f" ] && cp -p "$f" "$D/logs/"; done; \
find "$P/screenshots" -maxdepth 1 -name '*.png' -newer mod/run/final/.step-start -exec cp -p {} "$D/screenshots/" \; 2>/dev/null; \
echo "архивов: $(echo $G | wc -w), кадров: $(ls "$D/screenshots" | wc -l)"; ls -l "$D/logs"; grep -a -c 'SCENARIO done' "$D/logs/full.log"
```
Должно быть `SCENARIO done` ≥ 1 (у прогона, убитого тайм-аутом, — 0: так и прислать).

## A. Залп: ракета и 30 шахедов, тики в окне удара (#146, корень 1)
Шаги повторяют игру 30.09: встать у ZentixUA, ракета по 250 63 -147, перенос к месту ENOTzRPG, 30 шахедов
с разбросом 50. Прогон (тайм-аут вызова 45 мин):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final && touch mod/run/final/.step-start && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 40m tools/laptop_job.sh final-salvo -- python3 tools/prod_client.py strike-profile --world "Greenfield v0.5.4" --seconds 1500 \
  --prop 'airstrike.profile.steps=tp -668 87 -151; missile 1 0 250 63 -147; tp -271 71 -1141; drone 30 50 -272 72 -1142'; echo "код $?"
```
Выжимка (скрипт `results.py` — как в задании #146: шаги; `second` в окнах [шаг − 5 с, шаг + 30 с]; до 15 самых
долгих tick/period от 100 мс на окно; до 40 строк `Попадания (`):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final/A && cat > mod/run/final/A/results.py <<'EOT'
import re, sys
lines = open(sys.argv[1], encoding="utf-8", errors="replace").read().splitlines()
def sec(l):
    m = re.search(r"(\d\d):(\d\d):(\d\d)\.(\d+)\]", l)
    return int(m[1]) * 3600 + int(m[2]) * 60 + int(m[3]) + float("0." + m[4]) if m else None
def short(l):
    m = re.search(r"(\d\d:\d\d:\d\d\.\d+)\]", l)
    body = re.sub(r"^.*?\]: ", "", l).replace("SCENARIO strike-profile ", "")
    return ((m[1] + " ") if m else "") + re.sub(r" after «[^»]*»", "", body)[:200]
steps, day, prev = [], 0, None
for l in lines:
    if "SCENARIO strike-profile step" in l:
        s = sec(l)
        if prev is not None and s + day < prev: day += 86400
        prev = s + day; steps.append((s + day, l))
def when(l, ref):
    s = sec(l)
    if s is None: return None
    d0 = ref - ref % 86400
    return min((s + d for d in (d0 - 86400, d0, d0 + 86400)), key=lambda x: abs(x - ref))
def ms(l):
    m = re.search(r"strike-profile (tick|period) (\d+) ms", l)
    return int(m[2]) if m else 0
print("== шаги")
for _, l in steps: print(short(l))
for s, l in steps:
    near = [x for x in lines if "SCENARIO strike-profile" in x and (w := when(x, s)) is not None and s - 10 <= w < s + 60]
    print(f"\n== окно: {short(l)}")
    for x in near:
        if "strike-profile second" in x and s - 5 <= when(x, s) < s + 30: print(short(x))
    slow = sorted((x for x in near if ms(x) >= 100), key=lambda x: -ms(x))
    print(f"-- tick/period от 100 мс за [−10, +60 с]: {len(slow)} (до 15 самых долгих)")
    for x in slow[:15]: print(short(x))
hits = [l for l in lines if ("Попадания (" in l or "Планировщик" in l or "WorkScheduler" in l) and "[CHAT]" not in l]
print(f"\n== Попадания / планировщик: {len(hits)} (первые 40)")
for l in hits[:40]: print(short(l))
EOT
L=mod/run/final/A/logs/full.log && python3 mod/run/final/A/results.py "$L" > mod/run/final/A/results.txt; \
python3 tools/logscan.py "$L" --all > mod/run/final/A/logscan.txt; wc -lc mod/run/final/A/*.txt
```
**Проходит, если:** 4 строки `step` и `SCENARIO done`; в окнах ракеты и шахедов нет tick/period ≥ 250 мс, кроме 5 с после
`tp`; строк о медленном тике попаданий нет или каждая короче тика (50 мс) — готовность корня 1; logscan — без ошибок
мода. Прислать: `results.txt` целиком, из `logscan.txt` разделы «Ошибки» и «Отставание сервера».

## A2. Взрыв рядом с аппаратом Sable (корень 1, замер)
Зачем: взрыв, в охвате лучей которого есть аппарат, идёт ванильным `Explosion.explode()` целиком одной единицей
(миксин Sable толкает аппарат на каждом шаге луча). Шаг меряет, сколько стоит эта единица в сборке хоста
(подробно — `/mnt/project-files/perf/craft-explosion-step.md`). У места ENOTzRPG строится блок досок 3×3×5 над землёй,
собирается в аппарат (он падает и ложится на землю), игрок уходит на 200 блоков, ракета бьёт в 4–6 блоках от аппарата
(охват лучей ракеты ~27 блоков). Прогон (тайм-аут вызова 30 мин):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final && touch mod/run/final/.step-start && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 25m tools/laptop_job.sh final-craft -- python3 tools/prod_client.py commands --world "Greenfield v0.5.4" --seconds 900 \
  --prop 'airstrike.commands=gamemode spectator;tp @s -272 110 -1100;wait:400;fill -268 80 -1143 -266 84 -1141 oak_planks;sable assemble area -268 80 -1143 -266 84 -1141;wait:5;tp @s -272 130 -942;wait:400;airstrike salvo missile 1 0 at -272 72 -1142;wait:4800'; echo "код $?"
```
Выжимка:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final/A2 && L=mod/run/final/A2/logs/full.log && \
{ echo "== шаги, чат, удар"; grep -a -E 'SCENARIO /|\[CHAT\]|Airstrike/\]: Удар|SCENARIO done' "$L" | cut -c1-300 | head -40; \
  echo "== итог удара и попадания"; grep -a -E 'Итог удара|Попадания \(|Планировщик' "$L" | cut -c1-400 | head -20; \
  echo "== отставание"; grep -a -E "Can't keep up" "$L" | cut -c1-200 | head -10; \
  echo "== logscan"; python3 tools/logscan.py "$L" --all | sed -n '1,/^== Загрузка мода/p' | head -40; } > mod/run/final/A2/results.txt; wc -lc mod/run/final/A2/results.txt
```
**Замер, без провала:** команды выполнились (нет «Unknown or incomplete command», сборка аппарата без ошибки в чате);
из строки `Итог удара (…)` у -272 72 -1142 выписать `ванильных взрывов N` (ожидается ≥ 1 — иначе аппарат не попал
в охват, так и написать), `самая долгая единица X мс (вид)` и `шаги взрывов`; строку `Попадания (…)` того тика, если
он дольше 50 мс; есть ли `Can't keep up` в ±10 с от удара. Единица ванильного пути больше 100 мс — отдельная задача
координатору. Прислать `results.txt`.

## B. Шахеды после потери цели (#148)
Корова с постоянным UUID висит над местом ENOTzRPG; залп 30 шахедов с разбросом 50; корова дважды сдвигается на
60 блоков, погибает; через 3 с на 150 блоков восточнее появляется корова с тем же UUID. «;» внутри `[I;…]` сценарий
`commands` не режет (`ScenarioCommands`: разделитель — только «;» вне скобок и кавычек). Прогон (тайм-аут 30 мин):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final && touch mod/run/final/.step-start && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 25m tools/laptop_job.sh final-drones -- python3 tools/prod_client.py commands --world "Greenfield v0.5.4" --seconds 900 \
  --prop 'airstrike.commands=gamemode spectator;tp @s -271 130 -1141;wait:200;summon cow -272 110 -1142 {NoAI:1b,NoGravity:1b,PersistenceRequired:1b,UUID:[I;7001,7002,7003,7004],Tags:["drone_target"]};airstrike salvo drone 30 50 @e[tag=drone_target,limit=1];wait:200;tp @e[tag=drone_target] -212 110 -1142;wait:100;tp @e[tag=drone_target] -272 110 -1142;wait:200;kill @e[tag=drone_target];wait:60;summon cow -122 110 -1142 {NoAI:1b,NoGravity:1b,PersistenceRequired:1b,UUID:[I;7001,7002,7003,7004],Tags:["respawned"]};wait:2400;airstrike clear;execute if entity @e[tag=respawned]'; echo "код $?"
```
Выжимка:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final/B && L=mod/run/final/B/logs/full.log && \
{ echo "== шаги, чат, удары, снаряды"; grep -a -E 'SCENARIO /|\[CHAT\]|Airstrike/\]: (Удар|Снаряды):|SCENARIO done' "$L" | cut -c1-300 | head -80; \
  echo "== logscan"; python3 tools/logscan.py "$L" --all | sed -n '1,/^== Загрузка мода/p' | head -40; } > mod/run/final/B/results.txt; wc -lc mod/run/final/B/results.txt
```
**Проходит, если:** команды выполнились (нет «Unknown or incomplete command» / «do not have permission»); строка
«Удар: drone ×30 разброс 50 … (сущность minecraft:cow)»; через 1–2 с после `/kill` — строки «Снаряды: N × … потеряли
цель (пропала) …», в сумме N = 30, срок ≤ ~100 с; ни одной строки о потере цели после появления второй коровы;
`airstrike clear` — «total: 0» (или «0» по-русски); `execute if entity @e[tag=respawned]` — «Test passed»; logscan без
ошибок мода. Прислать `results.txt`.

## C. Видимость ракеты на подлёте (#149)
Сценарий `fx`, режим подлёта: ракета пускается от консоли встроенного сервера (стреляющего нет — заход прямой с севера,
последние 500 блоков); зритель в 40 блоках к востоку от цели, на 35 блоков выше самого высокого столба (крыши, кроны)
в 64 блоках от неё, ставится только над готовыми чанками клиента. Снаряд у клиента ищется по UUID (и заново после ухода
в полёт вне мира), удар — первый взрыв с этим снарядом в источнике, следующая цель — после удара. Кадры
`missile-N_flight_*` каждые 8 тиков до взрыва. Прогон (тайм-аут 45 мин):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final && touch mod/run/final/.step-start && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 40m tools/laptop_job.sh final-missile -- python3 tools/prod_client.py fx --world "Greenfield v0.5.4" --seconds 1800 \
  --prop "airstrike.fx.targets=missile@136,-495;missile@300,-420;missile@40,-640" --prop airstrike.fx.approach=true; echo "код $?"
```
Выжимка и кадры (кадры — копией, JPEG q85; контакт-лист ноутбук собирает сам: ряд на ракету, все `_flight` и первые
4 кадра взрыва):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final/C/frames && L=mod/run/final/C/logs/full.log && \
grep -a -E 'SCENARIO fx target|SCENARIO missile-[0-9]+ (reached client|blast at|impact|no impact|skipped)|SCENARIO done' "$L" | cut -c1-300 > mod/run/final/C/results.txt; \
grep -a 'ua.zentix.airstrike' "$L" | grep -E 'ERROR|Exception' | cut -c1-300 | head -20 >> mod/run/final/C/results.txt; \
for f in mod/run/final/C/screenshots/missile-*.png; do [ -e "$f" ] && ffmpeg -loglevel error -y -i "$f" -q:v 3 "mod/run/final/C/frames/$(basename "${f%.png}").jpg"; done; \
ls mod/run/final/C/frames | wc -l; cat mod/run/final/C/results.txt
```
**Проходит, если:** у каждой из 3 ракет в `fx target … viewer … (Block{minecraft:air} at eye)` — воздух у глаза;
`reached client at tick T1 … R from target`, `blast at … (D blocks from target)` с D ≤ 10 и `impact at tick T2`,
T2 − T1 ≥ 40, R — около прорисовки (~190), не 128; нет `no impact` и `skipped`; на кадрах корпус хотя бы в 1 `_flight` или шлейф хотя бы в 3, взрыв есть; нет
ERROR/исключений мода, есть `SCENARIO done`. Прислать строки T1, T2, R и контакт-лист (SendUserFile).

## D. Воронки: осыпание (#147)
(A) одна бомба в насыпную площадку 40×34×40 из камня, песка и гравия; (B) 3 бомбы с разбросом 100 там, где у хоста
30.09 Leaky писал «Detected large amount of entities». Команды — генератором; `F` — `bomber_flight_time` из шага 1
(по умолчанию 40). Генератор (сценарий `commands` сам ждёт 40 тиков после команды и 20 после снимка):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final/D && F=40 && cat > mod/run/final/D/gen.py <<'EOT'
import sys
F = int(sys.argv[1]) if len(sys.argv) > 1 else 40
cmds, t = [], 100
def cmd(c):
    global t; cmds.append(c); t += 40
def wait(n):
    global t; cmds.append(f"wait:{n}"); t += n
def shot(name):
    global t; cmds.append(f"shot:{name}"); t += 20
def window(tag, count, launch, dense, sparse):
    wait(max(0, launch + (F - 5) * 20 - t))
    end = t + dense * 20
    while t < end:
        cmd(count); shot(f"crater_{tag}_{(t - launch) // 20:03d}s")
    end = t + sparse * 20; i = 0
    while t < end:
        if i % 5 == 0: shot(f"crater_{tag}_{(t - launch) // 20:03d}s")
        cmd(count); i += 1
cmd("gamemode spectator"); cmd("tp @s 2640 225 1051 facing 2600 212 1051"); wait(300)
cmd("fill 2580 180 1031 2619 199 1070 stone"); cmd("fill 2580 200 1031 2619 209 1070 sand")
cmd("fill 2580 210 1031 2619 213 1070 gravel"); wait(40)
launch = t; cmd("airstrike salvo bunker 1 0 at 2600 214 1051")
window("a", "execute if entity @e[type=falling_block,x=2560,y=-64,z=1011,dx=80,dy=400,dz=80]", launch, 45, 30)
cmd("tp @s -400 140 -1745 facing -450 60 -1745"); wait(300)
launch = t; cmd("airstrike salvo bunker 3 100 at -450 60 -1745")
window("b", "execute if entity @e[type=falling_block]", launch, 60, 40)
wait(200)
open("mod/run/final/D/commands.txt", "w").write(";".join(cmds))
print(f"команд {len(cmds)}, тиков сценария {t + 100} (≈{(t + 100) // 20} с)")
EOT
python3 mod/run/final/D/gen.py "$F"
```
Прогон (тайм-аут 30 мин):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final && touch mod/run/final/.step-start && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 25m tools/laptop_job.sh final-crater -- python3 tools/prod_client.py commands --world "Greenfield v0.5.4" --seconds 1200 \
  --prop "airstrike.commands=$(cat mod/run/final/D/commands.txt)"; echo "код $?"
```
Выжимка:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && L=mod/run/final/D/logs/full.log && \
{ echo "== удары и Leaky"; grep -a -E 'Airstrike/\]: Удар|Detected large amount of entities|SCENARIO done' "$L" | cut -c1-300 | head -40; \
  echo "== падающие блоки (время и счёт)"; grep -a -E 'Test passed, count|Test failed|Проверка пройдена|Проверка не пройдена' "$L" | sed -E 's/^\[([0-9:]+)\].*[^0-9]([0-9]+)$/\1 \2/; s/^\[([0-9:]+)\].*(failed|не пройдена).*/\1 0/' | tr '\n' ' '; echo; \
  echo "== ошибки команд"; grep -a -E 'Unknown or incomplete command|do not have permission|That position is not loaded|Неизвестная|Недостаточно прав|не загружен' "$L" | head -5; \
  echo "== logscan"; python3 tools/logscan.py "$L" --all | sed -n '1,/^== Загрузка мода/p' | head -40; } > mod/run/final/D/results.txt; \
ls mod/run/final/D/screenshots/ | grep -c crater_; wc -lc mod/run/final/D/results.txt
```
**Проходит, если:** «Удар: bunker ×1 …» и «Удар: bunker ×3 …»; в (A) счёт хоть раз > 0 и ни разу > 48; в (B) ни разу
> 96; строк Leaky — 0; кадры: на 2 и 5 с у стенок падающие блоки, на 10 с стенки осели; logscan без ошибок мода.
Прислать `results.txt` и по кадру на ~2, ~5, ~10 с после первого ненулевого счёта в (A) и в (B)
(секунда — время строки минус время строки «Удар» окна; кадр `crater_<a|b>_NNNs_…` с ближайшей секундой).

## E. Карта наведения с DH (#150)
Сценарий `salvo-map`: ждёт LOD DH, открывает карту, 6 шахедов с разбросом 40 кликом в ~130 блоках, карта открыта
весь полёт. Прогон (тайм-аут 45 мин):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final && touch mod/run/final/.step-start && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 40m tools/laptop_job.sh final-map -- python3 tools/prod_client.py salvo-map --world "Greenfield v0.5.4" --seconds 1200; echo "код $?"
```
Выжимка:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final/E && L=mod/run/final/E/logs/full.log && \
{ grep -a -E 'Карта наведения:|Карта: первая плитка|Карта: источник|SCENARIO salvo-map|SCENARIO done' "$L" | cut -c1-600 | head -40; \
  grep -a "airstrike" "$L" | grep -iE "exception|error" | cut -c1-300 | head -20; ls mod/run/final/E/screenshots | grep salvo-map | head -40; } > mod/run/final/E/results.txt; \
mkdir -p mod/run/final/E/frames && cp -a mod/run/final/E/screenshots/salvo-map_* mod/run/final/E/frames/ 2>/dev/null; wc -lc mod/run/final/E/results.txt
```
**Проходит, если:** `Карта наведения: рельеф вида готов за N мс` при первом открытии — N ≤ 1000 и «сразу из памяти»
≥ половины плиток; пути 6 снарядов и район разброса читаются на городе; нет исключений с `airstrike` и строки
`Карта: источник dh не отдаёт плитку`. Стопка миниатюр снимков в правом верхнем углу кадра — уведомление о снимке
от другого мода сборки (не Airstrike; настройку в копии инстанса не нашли): часть круга под ней не оценивать. Прислать `results.txt` и 4 кадра (открытие, первый после клика,
два в полёте).

## F. Дальняя цель по карте (#145, корень 4)
Три прогона подряд; каждый — своя задача (`final-far1`, `final-far2`, `final-far3`), тайм-аут 30 мин,
выжимка сразу после каждого. `mapWeapon` — только латиницей.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final && touch mod/run/final/.step-start && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 25m tools/laptop_job.sh final-far1 -- python3 tools/prod_client.py target-map --world "Greenfield v0.5.4" --seconds 1200 \
  --prop airstrike.mapAt=250,-147 --prop airstrike.mapFrom=-350,150,-147 --prop airstrike.mapWeapon=missile; echo "код $?"
```
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final && touch mod/run/final/.step-start && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 25m tools/laptop_job.sh final-far2 -- python3 tools/prod_client.py target-map --world "Greenfield v0.5.4" --seconds 1200 \
  --prop airstrike.mapAt=250,-147 --prop airstrike.mapFrom=-350,150,-147 --prop airstrike.mapWeapon=rocket; echo "код $?"
```
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final && touch mod/run/final/.step-start && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 25m tools/laptop_job.sh final-far3 -- python3 tools/prod_client.py target-map --world "Newisle v1.3.1" --seconds 1200 \
  --prop airstrike.mapAt=83,-370 --prop airstrike.mapFrom=83,150,230 --prop airstrike.mapWeapon=missile; echo "код $?"
```
Выжимка после каждого (`N` — 1, 2 или 3):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && N=1 && mkdir -p "mod/run/final/F$N/frames" && L="mod/run/final/F$N/logs/full.log" && \
{ grep -a -E 'SCENARIO map-target (selected|flight|impact|no impact)|Airstrike/\]: Удар|SCENARIO done' "$L" | cut -c1-400; \
  echo "== logscan"; python3 tools/logscan.py "$L" --all | sed -n '1,/^== Загрузка мода/p' | head -30; } > "mod/run/final/F$N/results.txt"; \
for f in $(ls -t "mod/run/final/F$N/screenshots"/target-map_*.png 2>/dev/null | head -2); do ffmpeg -loglevel error -y -i "$f" -q:v 3 "mod/run/final/F$N/frames/$(basename "${f%.png}").jpg"; done; \
wc -lc "mod/run/final/F$N/results.txt"; ls "mod/run/final/F$N/frames"
```
**Проходит, если:** в 1–2 `distance` > 400 и `terrain height` есть; в 1 Y в `Удар:` = высота карты − 0,5 (≈106,5 у крыши
107), не 63, взрыв на крыше (Y в пределах 2 блоков от «земли под взрывом», промах ≤ 2); в 2 РСЗО рвётся на крыше
(Y в пределах 2; строка `impact … miss M (допуск T) — OK`: допуск РСЗО — 2 блока и три СКО её рассеивания из паспорта,
1 % дальности от пакета у игрока); в 3 Y в `Удар:` не ниже 62 (уровень моря − 1: при пустой карте клиента приказ берёт
первый воздух 63, точка — середина верхнего блока, лог пишет его Y), взрыв на поверхности, `impact … — OK`; logscan без ошибок мода. Прислать строки 1–4 из `results.txt` каждого прогона, logscan и 2 кадра каждого.

## G. Ядерка 15 кт в воздухе (#138, корень 1)
**Ждёт задание nuke-gate4** (тред «Ядерный взрыв: ударная волна»): его критерии и камеры заменят этот шаг; до его
SHA шаг ниже — прежний, и координатор не отправляет задание, пока здесь этот абзац.
Последней: самая тяжёлая. Удар по копии Greenfield (её делает `prod_client.py`; мир фильма и его инстанс не
трогаются). Камеры — как в задании #138: ближняя в 1 км (y 130), дальняя в 4 км, руины по кругу r = 320 на y 220.
Время кадров считается от подрыва: пуск + `N` тиков (`flight_time` из шага 1, по умолчанию 1800) — генератор
подставляет его сам:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final/G && N=1800 && \
python3 - "$N" > mod/run/final/G/commands.txt <<'EOT'
import sys
N = int(sys.argv[1])
# сценарий commands ждёт 40 тиков после каждой команды и 20 после снимка: до подрыва от пуска — N тиков
print(";".join([
    "hud:off", "gamemode spectator", "tp @s -863.5 130 -496.4 -90 -10", "wait:600",
    "airstrike nuke at 136.5 69 -495.5 15 air", f"wait:{N - 30}",
    "shot:flash", "wait:10", "shot:fireball", "wait:80", "shot:wave", "wait:120", "shot:close_ruins",
    "tp @s -3803 120 -499 -90 -5", "wait:100", "shot:far_dh", "tp @s -3803 120 -499 -90 -30", "wait:1020", "shot:glow70",
    "tp @s 456.5 220 -495.5 90 -30", "wait:200", "shot:ruins_e", "tp @s 136.5 220 -175.5 180 -30", "wait:200", "shot:ruins_s",
    "tp @s -183.5 220 -495.5 -90 -30", "wait:200", "shot:ruins_w", "tp @s 136.5 220 -815.5 0 -30", "wait:200", "shot:ruins_n", "wait:200"]))
EOT
wc -c mod/run/final/G/commands.txt
```
Прогон (тайм-аут вызова 50 мин):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final && touch mod/run/final/.step-start && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 45m tools/laptop_job.sh final-nuke -- python3 tools/prod_client.py commands --world "Greenfield v0.5.4" --seconds 2400 \
  --prop "airstrike.commands=$(cat mod/run/final/G/commands.txt)"; echo "код $?"
```
Выжимка, кадры, куча:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && P=mod/run/final/G && L=$P/logs/full.log && mkdir -p mod/run/final/G/frames && \
for n in flash fireball wave close_ruins far_dh glow70 ruins_e ruins_s ruins_w ruins_n; do \
  f=$(ls -t "$P/screenshots/${n}"_[0-9]*.png 2>/dev/null | head -1); \
  if [ -n "$f" ]; then ffmpeg -loglevel error -y -i "$f" -q:v 3 "mod/run/final/G/frames/$n.jpg" && echo "$n ← $(basename "$f")"; else echo "$n: кадра нет"; fi; \
done; \
python3 tools/logscan.py "$L" --all > mod/run/final/G/logscan.txt; \
grep -E 'Руины удара №|Подрыв №|Руины подрыва №|Руины: |Ядерный тик|Can.t keep up|дальше руины заранее не строятся|Distant Horizons|LevelChunkEditsMixin|SCENARIO' "$L" | grep -v 'POI data mismatch' | cut -c1-400 > mod/run/final/G/lines.txt; \
echo "POI data mismatch: $(grep -c 'POI data mismatch' "$L")" >> mod/run/final/G/lines.txt; \
[ -e "$P/logs/gc.log" ] && grep -E 'Pause (Young|Old|Full)|Garbage Collection|Major Collection|Minor Collection|->' "$P/logs/gc.log" | tail -400 > mod/run/final/G/gc.txt; \
wc -lc mod/run/final/G/*.txt
```
**Сравнение с видео Silo — только на ноутбуке**: десять кадров `mod/run/final/G/frames/*.jpg` сравнить с видео здесь;
видео и кадры из него никуда не уходят, наружу — строка текста на кадр.
**Проходит, если:** отставание руин «у игроков» ≤ 40 тиков, по готовому плану ≥ 90 % чанков тяжёлой зоны; самый
долгий тик после подрыва ≤ 250 мс (кроме тика подрыва и 5 с после `tp` на дальнюю камеру); куча — пик заметно ниже
8 ГБ, без Full GC; нет строк страховки памяти, DH и счётчика изменений; `POI data mismatch` — 0; logscan без новых
ошибок; кадры: flash залит светом, fireball — шар, wave — стена пыли идёт к камере (за ней руины, перед ней целое),
close_ruins — стена прошла, крыши и стёкла побиты; far_dh — город в LOD руинами; glow70 — гриб светится; ruins_* —
башни у эпицентра рухнули, ничего не висит в воздухе, скалы, берега и вода целы, нет зазубрин по границам чанков.
Прислать: строки руин из `lines.txt` дословно, `POI data mismatch`, самый долгий тик за 60 с до и после подрыва,
пик кучи и Full GC, ошибки logscan (или «ноль»), по строке о каждом кадре и строку сравнения с Silo;
10 JPEG — SendUserFile.

## После всех шагов
Прислать координатору одной строкой на шаг: `A ok|FAIL …`, `A2 <цифры замера>`, … `G ok|FAIL …` (FAIL — с пунктом критерия), и вывод:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && git status --short | head; ls mod/run/final; du -sh mod/run/prod
```
Worktree и копия мира остаются в `$W` до слова Артёма «Разрешаю уборку на ноутбуке» в треде «Ноутбук».
Любой FAIL — ничего не переделывать и не повторять: решение в треде того корня или PR.
