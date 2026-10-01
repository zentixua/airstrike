# Ноутбук: финальная проверка кандидата перед выпуском (одна, полная сборка), версия 5

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
git log --oneline -1 && [ "$(git rev-parse HEAD)" = <SHA> ] && echo "коммит верный" && test -f tools/logtime.py && echo "logtime.py есть" && \
grep -q 'strike-profile hit' mod/src/devtest/java/ua/zentix/airstrike/scenario/StrikeProfile.java && grep -q 'strike-profile clock utc' mod/src/devtest/java/ua/zentix/airstrike/scenario/StrikeProfile.java && grep -q 'airstrike.commands.gap' mod/src/devtest/java/ua/zentix/airstrike/scenario/ClientScenario.java && echo "сценарии v5" && \
grep -hoE '"(strike-profile|commands|fx|salvo-map|target-map)"' mod/src/devtest/java/ua/zentix/airstrike/scenario/ClientScenario.java | sort -u
```
Должно быть «коммит верный», «logtime.py есть», «сценарии v5» и пять имён сценариев: `commands`, `fx`, `salvo-map`, `strike-profile`, `target-map`.
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
{ test ! -e "$D/logs/full.log" || { echo "стоп: full.log уже есть"; exit 1; }; } && mkdir -p "$D/logs" "$D/screenshots" && \
G=$(find "$P/logs" -maxdepth 1 -name '*.log.gz' -newer mod/run/final/.step-start | sort -V) && \
{ for g in $G; do zcat "$g"; done; cat "$P/logs/latest.log"; } > "$D/logs/full.log" && \
for f in $G "$P/logs/latest.log" "$P/logs/gc.log"; do [ -e "$f" ] && cp -pn "$f" "$D/logs/"; done; \
find "$P/screenshots" -maxdepth 1 -name '*.png' -newer mod/run/final/.step-start -exec cp -pn {} "$D/screenshots/" \; 2>/dev/null; \
echo "архивов: $(echo $G | wc -w), кадров: $(ls "$D/screenshots" | wc -l)"; ls -l "$D/logs"; grep -a -c 'SCENARIO done' "$D/logs/full.log"
```
Должно быть `SCENARIO done` ≥ 1 (у прогона, убитого тайм-аутом, — 0: так и прислать).

**Время строк лога** во всех выжимках разбирает один `tools/logtime.py` из worktree кандидата: строка начинается
с `[ЧЧ:ММ:СС]`, `[ЧЧ:ММ:СС.ммм]` или (боевой клиент) `[01Oct2026 ЧЧ:ММ:СС.ммм]`, через полночь время идёт дальше.
Из оболочки — `python3 tools/logtime.py ЛОГ РЕГВЫР [--since РЕГВЫР]`: строки с РЕГВЫР как «ЧЧ:ММ:СС.ммм +С.с текст»
(+С.с — секунды от первой строки с `--since`); скрипты выжимок берут его модулем. `sed`/`cut` по началу строки
для времени не годятся.

## A. Залп: ракета и 30 шахедов, тики в окне удара (#146, корень 1)
Шаги повторяют игру 30.09: встать у ZentixUA, ракета по 250 63 -147, перенос к месту ENOTzRPG, 30 шахедов
с разбросом 50 (точка каждого — в круге радиуса 50 от центра). Клиент пишет GC-лог (`prod/logs/gc.log`, его
копирует блок «Лог и кадры шага») и JFR (`mod/run/final/A/A.jfr`, до 1 ГБ; остаётся на ноутбуке, наружу — только
выжимка). Прогон (тайм-аут вызова 45 мин):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final/A/jfr-repo && touch mod/run/final/.step-start && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 40m tools/laptop_job.sh final-salvo -- python3 tools/prod_client.py strike-profile --world "Greenfield v0.5.4" --seconds 1500 \
  --prop 'airstrike.profile.steps=tp -668 87 -151; missile 1 0 250 63 -147; tp -271 71 -1141; drone 30 50 -272 72 -1142' \
  --jvm="-XX:StartFlightRecording=filename=$W/mod/run/final/A/A.jfr,settings=profile,maxsize=1g,dumponexit=true" \
  --jvm="-XX:FlightRecorderOptions:repository=$W/mod/run/final/A/jfr-repo"; echo "код $?"; ls -l mod/run/final/A/A.jfr
```
Выжимка (скрипт `results.py`: шаги; `second` в окнах [шаг − 5 с, шаг + 30 с]; до 15 самых долгих tick/period от 100 мс
на окно; разрывы ≥ 250 мс — с паузами GC из `gc.log` и автосохранением; попадания снарядов; до 40 строк `Попадания (`).
**Разрыв** — строка `tick` (тик сервера) или `period` (от начала тика до начала следующего: туда входят и задачи потока
сервера между тиками, и всё, что поток не работал) от 250 мс. Объяснён, если это автосохранение мира (кончился не позже
1 с после строки «Saving sub-levels») или без пауз GC, попавших в его промежуток, он короче 250 мс. Часы лога, `gc.log`
и JFR у клиента бывают в разных поясах (v4: лог +3, `gc.log` +2) — всё сравнивается в UTC: сдвиг лога — из строки
`strike-profile clock utc …`, у паузы GC — из её метки (заголовок раздела разрывов показывает оба). Нет строки `clock` —
`results.py` выходит с кодом 3 (сбой задания, сообщить). Необъяснённые промежутки `results.py` пишет в `gaps.txt` для
разбора JFR (блок ниже):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final/A && cat > mod/run/final/A/results.py <<'EOT'
import re, sys
sys.path.insert(0, "tools"); import logtime
rows = logtime.timeline(logtime.read(sys.argv[1]))
gc_path, gaps_path = sys.argv[2], sys.argv[3]
def short(t, l):
    body = logtime.body(l).replace("SCENARIO strike-profile ", "")
    return logtime.stamp(t) + " " + re.sub(r" after «[^»]*»", "", body)[:200]
def ms(l):
    m = re.search(r"strike-profile (tick|period) (\d+) ms", l)
    return int(m[2]) if m else 0
steps = [(t, l) for t, l in rows if "SCENARIO strike-profile step" in l]
# автосохранение мира (Sable пишет «Saving sub-levels»): тик, кончившийся в 1 с после строки, — не мода
saves = [t for t, l in rows if "Saving sub-levels" in l]
def autosave(t): return any(s <= t <= s + 1 for s in saves)
# Часы: лог, gc.log и JFR одного клиента бывают в разных поясах (ноутбук хоста: лог +3, gc.log +2, `jfr print` +3),
# поэтому всё сравнивается в UTC (секунды суток). Сдвиг лога — из строки «strike-profile clock utc …» (настоящее время
# рядом с временем строки), у каждой строки gc.log — свой сдвиг в её метке.
def utc_sec(hh, mm, ss, frac, off):
    s = int(hh) * 3600 + int(mm) * 60 + int(ss) + float("0." + (frac or "0"))
    if off and off != "Z":
        o = off.replace(":", ""); s -= (1 if o[0] == "+" else -1) * (int(o[1:3]) * 3600 + int(o[3:5]) * 60)
    return s
log_off = None
for t, l in rows:
    if m := re.search(r"strike-profile clock utc \d{4}-\d\d-\d\dT(\d\d):(\d\d):(\d\d)(?:\.(\d+))?Z", l):
        d = (t - utc_sec(*m.groups(), None)) % 86400
        log_off = round((d if d < 50400 else d - 86400) / 900) * 900  # пояса кратны 15 мин
        break
def utc(t): return t - log_off
# паузы GC: «[2026-10-01T04:14:19.100+0200][123.456s] GC(12) Pause Young … 23.456ms» — время строки = конец паузы
pauses, gc_offs, gc_bad = [], set(), 0
try:
    for g in open(gc_path, encoding="utf-8", errors="replace"):
        m = re.match(r"\[\d{4}-\d\d-\d\dT(\d\d):(\d\d):(\d\d)\.(\d+)([+-]\d{4}|Z)?\]", g)
        d = re.search(r"Pause.*?(\d+(?:\.\d+)?)ms\s*$", g)
        if not m or not d: continue
        if not m[5]: gc_bad += 1; continue  # без пояса в метке время не сопоставить
        gc_offs.add(m[5])
        end = utc_sec(*m.groups())
        pauses.append((end - float(d[1]) / 1000, end, float(d[1])))
except FileNotFoundError:
    print(f"!! нет {gc_path}: паузы GC не сопоставлены")
if gc_bad: print(f"!! строк пауз GC без пояса в метке: {gc_bad} — не сопоставлены")
def gc_in(a, b):
    """Сколько мс пауз GC внутри [a, b] (секунды суток UTC; сутки — по модулю)."""
    s = 0.0
    for p0, p1, _ in pauses:
        k = round((a - p0) / 86400)  # тот же день, что у промежутка
        p0, p1 = p0 + k * 86400, p1 + k * 86400
        s += max(0.0, min(b, p1) - max(a, p0)) * 1000
    return s
first = steps[0][0] if steps else 0
print("== шаги")
for t, l in steps: print(short(t, l))
for s, l in steps:
    near = [(t, x) for t, x in rows if "SCENARIO strike-profile" in x and s - 10 <= t < s + 60]
    print(f"\n== окно: {short(s, l)}")
    for t, x in near:
        if "strike-profile second" in x and s - 5 <= t < s + 30: print(short(t, x))
    slow = sorted((r for r in near if ms(r[1]) >= 100 and r[0] >= first), key=lambda r: -ms(r[1]))
    print(f"-- tick/period от 100 мс за [−10, +60 с], с первого шага: {len(slow)} (до 15 самых долгих)")
    for t, x in slow[:15]: print(short(t, x) + ("  [автосохранение]" if autosave(t) else ""))
def zone(o): return "?" if o is None else f"{'+' if o >= 0 else '-'}{abs(o) // 3600:02d}{abs(o) % 3600 // 60:02d}"
print(f"\n== разрывы от 250 мс с первого шага (GC-пауз в логе: {len(pauses)}; часы: лог {zone(log_off)}, GC {' '.join(sorted(gc_offs)) or '?'})")
if log_off is None: print("!! нет строки «strike-profile clock utc» — часы лога неизвестны, паузы GC и окна JFR не сопоставить")
tps = [t for t, l in steps if "step «tp " in l]
ticks = [(t, ms(l)) for t, l in rows if "strike-profile tick " in l]
bad = []
for t, l in rows:
    n = ms(l)
    if n < 250 or t < first or "strike-profile" not in l: continue
    # period, в который входит уже показанный долгий тик (тот же разрыв), — не второй раз
    if "period" in l and any(t - 0.1 <= u <= t and m >= n - 50 for u, m in ticks): continue
    a, b = t - n / 1000, t
    g = None if log_off is None else gc_in(utc(a), utc(b))
    gs = "?" if g is None else f"{g:.0f}"
    if autosave(t): why = "автосохранение"
    elif any(p <= t <= p + 5 for p in tps): why = "5 с после tp"
    elif g is not None and n - g < 250: why = f"GC {gs} мс"
    else:
        why = f"НЕ ОБЪЯСНЁН (GC {gs} мс)"; bad.append((a, b))
    print(f"{short(t, l)}  — {why}")
if not bad: print("необъяснённых нет")
# gaps.txt: начало и конец в UTC (секунды суток), как и время JFR после разбора его метки, и сдвиг лога (для строк по логу)
if log_off is not None:
    with open(gaps_path, "w") as f:
        for a, b in bad: f.write(f"{utc(a) % 86400:.3f} {utc(a) % 86400 + (b - a):.3f} {log_off}\n")
print(f"необъяснённых: {len(bad)}")
hits = [(t, l) for t, l in rows if "SCENARIO strike-profile hit " in l]
far = [(t, l) for t, l in hits if (m := re.search(r": (\d+) blocks horizontal, (-?\d+) above", l)) and (int(m[1]) > 10 or abs(int(m[2])) > 10)]
print(f"\n== попадания снарядов: {len(hits)}, дальше 10 блоков от своей точки: {len(far)}")
for t, l in hits: print(short(t, l))
hl = [(t, l) for t, l in rows if ("Попадания (" in l or "Планировщик" in l or "WorkScheduler" in l) and "[CHAT]" not in l]
print(f"\n== Попадания / планировщик: {len(hl)} (первые 40)")
for t, l in hl[:40]: print(short(t, l))
if log_off is None: sys.exit(3)  # сбой задания: без часов лога разрывы не разобрать
EOT
D=mod/run/final/A && L=$D/logs/full.log && python3 $D/results.py "$L" $D/logs/gc.log $D/gaps.txt > $D/results.txt; echo "results.py: код $?"; \
python3 tools/logscan.py "$L" --all > $D/logscan.txt; wc -lc $D/*.txt
```
Только если в `results.txt` есть «НЕ ОБЪЯСНЁН» (`gaps.txt` не пуст): что делал поток сервера в этих
промежутках (до 8 самых долгих), по JFR — задачей `final-jfr`, куча `jfr` до 2 ГБ, стек до 64 кадров (в фоне, тайм-аут
вызова 35 мин; код не 0 — сбой, сообщить). Время события JFR переводится в UTC по поясу в его же метке (`jfr print`
пишет «+03:00» или «Z»), промежутки в `gaps.txt` — уже в UTC. Разрыв, у которого больше половины выборок потока
сервера (и не меньше 5) — внутри DataFixerUpper (`com.mojang.datafixers`, `net.minecraft.util.datafix`), — **объяснён:
ванильное обновление мира** (сервер переводит старые данные чанков и сущностей мира в формат 1.21.1; мод DataFixerUpper
не зовёт). Выжимка пишет у каждого разрыва долю таких выборок, вердикт, самые частые стеки, первые кадры вне JDK
и DataFixerUpper (откуда работа) и ожидания; в конце — «необъяснённых после JFR: K»:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && D=mod/run/final/A && cat > $D/jfrwin.py <<'EOT'
# Поток «Server thread» в необъяснённых разрывах: stdin — `jfr print --json`, аргумент — gaps.txt (начало, конец — UTC,
# секунды суток; сдвиг часов лога в секундах).
import collections, json, re, sys
gaps = [tuple(map(float, l.split())) for l in open(sys.argv[1]) if l.strip()]
# не больше 8 самых долгих разрывов: выжимка остаётся короткой
dropped = max(0, len(gaps) - 8)
gaps = sorted(sorted(gaps, key=lambda g: g[0] - g[1])[:8])
no_zone = 0
def sec(ts):  # "2026-10-01T07:22:03.123456789+03:00" или "…Z" -> секунды суток UTC по поясу самой метки
    global no_zone
    m = re.search(r"T(\d\d):(\d\d):(\d\d)(?:\.(\d+))?(Z|[+-]\d\d:?\d\d)?", ts)
    s = int(m[1]) * 3600 + int(m[2]) * 60 + int(m[3]) + float("0." + (m[4] or "0"))
    if not m[5]: no_zone += 1
    elif m[5] != "Z":
        o = m[5].replace(":", ""); s -= (1 if o[0] == "+" else -1) * (int(o[1:3]) * 3600 + int(o[3:5]) * 60)
    return s % 86400
def dur(v):
    d = v.get("duration")
    if isinstance(d, (int, float)): return d / 1e6
    m = re.match(r"PT(?:(\d+)M)?([\d.]+)S", str(d or ""))
    return (int(m[1] or 0) * 60 + float(m[2])) * 1000 if m else 0.0
def name(f): return f["method"]["type"]["name"].replace("/", ".")
def frame(f): return name(f) + "." + f["method"]["name"] + ":" + str(f.get("lineNumber", ""))
DFU = ("com.mojang.datafixers.", "net.minecraft.util.datafix.")
NOISE = ("java.", "jdk.", "sun.", "com.google.common.", "it.unimi.") + DFU
def dfu(fr): return any(name(f).startswith(DFU) for f in fr)
def origin(fr):  # первые кадры вне JDK, библиотек и DataFixerUpper: чья это работа
    own = [f for f in fr if not name(f).startswith(NOISE) and "LambdaForm" not in name(f) and "$$Lambda" not in name(f)]
    return " <- ".join(frame(f) for f in own[:4])[:300] if own else "— (все кадры в JDK/DataFixerUpper)"
# [выборок, из них в DataFixerUpper, стеки, откуда, ожидания]
stat = [[0, 0, collections.Counter(), collections.Counter(), {}] for _ in gaps]
def event(e):
    v = e["values"]
    if (v.get("sampledThread") or v.get("eventThread") or {}).get("javaName") != "Server thread": return
    t0 = sec(v["startTime"]); fr = (v.get("stackTrace") or {}).get("frames") or []
    for i, (a, b, _) in enumerate(gaps):
        k = round((a - t0) / 86400) * 86400
        if e["type"] == "jdk.ExecutionSample":
            if a <= t0 + k <= b and fr:
                st = stat[i]; st[0] += 1; st[1] += dfu(fr)
                st[2][" <- ".join(frame(f) for f in fr[:6])[:300]] += 1; st[3][origin(fr)] += 1
        elif t0 + k < b and t0 + k + dur(v) / 1000 > a:
            w = stat[i][4]; key = f"{e['type'][4:]}: " + (" <- ".join(frame(f) for f in fr[:6])[:300] if fr else "—")
            w[key] = (w.get(key, (0, 0.0))[0] + 1, w.get(key, (0, 0.0))[1] + dur(v))
buf, inside = [], False
for line in sys.stdin:
    if not inside:
        if line.strip().endswith('"events": [{'): inside, buf = True, ["{"]
        continue
    if line.rstrip() in ("    }, {", "    }]"):
        buf.append("}"); event(json.loads("".join(buf)))
        if line.rstrip() == "    }]": break
        buf = ["{"]
    else:
        buf.append(line)
def hms(t): t %= 86400; return f"{int(t // 3600):02d}:{int(t % 3600 // 60):02d}:{t % 60:06.3f}"
print(f"разрывов: {len(gaps) + dropped}, разобраны {len(gaps)} самых долгих, отброшено {dropped}")
if no_zone: print(f"!! событий JFR без пояса в метке: {no_zone} — считаны как UTC")
left = dropped
for (a, b, off), (n, nd, stacks, origins, waits) in zip(gaps, stat):
    upgrade = n >= 5 and nd * 2 > n
    left += not upgrade
    verdict = "объяснён: ванильное обновление мира" if upgrade else "НЕ ОБЪЯСНЁН"
    print(f"\n=== разрыв {hms(a + off)}–{hms(b + off)} по логу ({hms(a)} UTC, {(b - a) * 1000:.0f} мс): выборок потока сервера {n}, "
          f"в DataFixerUpper {nd} — {verdict}")
    for k, c in stacks.most_common(8): print(f"{c:4d} {k}")
    print("-- откуда (первые кадры вне JDK и DataFixerUpper):")
    for k, c in origins.most_common(4): print(f"{c:4d} {k}")
    for k, (c, ms) in sorted(waits.items(), key=lambda kv: -kv[1][1])[:8]: print(f"ожидание ×{c}, {ms:.0f} мс — {k}")
print(f"\nнеобъяснённых после JFR: {left}" + (f" (из них не разобрано {dropped})" if dropped else ""))
EOT
J="$JAVA_HOME/bin/jfr"; [ -x "$J" ] || J="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin/jfr"; \
timeout -k 60 30m tools/laptop_job.sh final-jfr -- bash -c 'set -o pipefail; "$0" -J-Xmx2g print --json --stack-depth 64 --events jdk.ExecutionSample,jdk.ThreadPark,jdk.JavaMonitorEnter,jdk.ThreadSleep,jdk.FileRead,jdk.FileWrite,jdk.SafepointBegin "$1" | python3 "$2" "$3" > "$4"' \
  "$J" $D/A.jfr $D/jfrwin.py $D/gaps.txt $D/jfrwin.txt; echo "код $?"; wc -lc $D/jfrwin.txt
```
Остановить — только `systemctl --user stop 'airstrike-job-final-jfr-*'`.
**Проходит, если:** 4 строки `step` и `SCENARIO done`; в окнах ракеты и шахедов нет **необъяснённых** разрывов (тики
при входе в мир до первого шага и 5 с после `tp` не считаются); автосохранение (`/mnt/project-files/tick-baseline-68986c0.txt`:
у базовой линии без ударов тот же тик 213 мс) и разрывы, объяснённые GC, — в итог отдельной строкой для сведения;
строк о медленном тике попаданий нет или каждая короче тика (50 мс) — готовность корня 1; logscan — без ошибок мода.
Разрыв, который `jfrwin.txt` объяснил ванильным обновлением мира, — не провал, в итог для сведения (мир Greenfield
заранее не обновляется, эталон тот же). Необъяснённый и после JFR (или не разобранный сверх 8) — FAIL шага: прислать
и `jfrwin.txt`, решение — у координатора.
**Попадания снарядов** (для сведения, не критерий): по строке `hit` на снаряд — где он был при первом взрыве, фаза,
взведён ли, курс, своя точка цели, расстояние до неё и блок, в который попал; «дальше 10 блоков от своей точки» —
врезался по пути. Прислать: `results.txt` целиком, `jfrwin.txt` (если был), из `logscan.txt` разделы «Ошибки»
и «Отставание сервера».

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
60 блоков, погибает; через 47 с — когда весь залп уже пущен (30 пусков через 20–40 тиков — до 58 с от приказа,
`/kill` — через ~31 с) — на 150 блоков восточнее появляется корова с тем же UUID: шахеды в полёте не должны взять
её заново. Залп следит за целью каждый тик (#163, `SalvoData.Salvo.watchCenter`): корова погибла — остаток залпа
(ещё не пущенные шахеды) идёт в точку, где её видели последний раз, строка «Залп: drone — цель … погибла, остаток (R) — по её
последней точке …», и поздние пуски цели-сущности уже не имеют (строк о потере не пишут) — они в той же сумме. «;» внутри `[I;…]` сценарий
`commands` не режет (`ScenarioCommands`: разделитель — только «;» вне скобок и кавычек). Прогон (тайм-аут 30 мин):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final && touch mod/run/final/.step-start && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 25m tools/laptop_job.sh final-drones -- python3 tools/prod_client.py commands --world "Greenfield v0.5.4" --seconds 900 \
  --prop 'airstrike.commands=gamemode spectator;tp @s -271 130 -1141;wait:200;summon cow -272 110 -1142 {NoAI:1b,NoGravity:1b,PersistenceRequired:1b,UUID:[I;7001,7002,7003,7004],Tags:["drone_target"]};airstrike salvo drone 30 50 @e[tag=drone_target,limit=1];wait:200;tp @e[tag=drone_target] -212 110 -1142;wait:100;tp @e[tag=drone_target] -272 110 -1142;wait:200;kill @e[tag=drone_target];wait:900;summon cow -122 110 -1142 {NoAI:1b,NoGravity:1b,PersistenceRequired:1b,UUID:[I;7001,7002,7003,7004],Tags:["respawned"]};wait:2400;airstrike clear;execute if entity @e[tag=respawned]'; echo "код $?"
```
Выжимка:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final/B && L=mod/run/final/B/logs/full.log && \
{ echo "== шаги, чат, удары, снаряды"; grep -a -E 'SCENARIO /|\[CHAT\]|Airstrike/\]: (Удар|Снаряды|Залп):|SCENARIO done' "$L" | cut -c1-300 | head -80; \
  echo "== по времени"; python3 tools/logtime.py "$L" 'SCENARIO /(kill|summon)|потеряли цель|погибла, остаток|Итог удара' --since 'SCENARIO /airstrike salvo' | cut -c1-260; \
  echo "== logscan"; python3 tools/logscan.py "$L" --all | sed -n '1,/^== Загрузка мода/p' | head -40; } > mod/run/final/B/results.txt; wc -lc mod/run/final/B/results.txt
```
**Проходит, если:** команды выполнились (нет «Unknown or incomplete command» / «do not have permission»); строка
«Удар: drone ×30 разброс 50 … (сущность minecraft:cow)»; через 0–2 с после `/kill` — одна строка «Залп: drone — цель …
погибла, остаток (R) — по её последней точке» и строка «Снаряды: N × … потеряли цель (пропала) …» (шахеды в полёте; до
`/summon … respawned` других строк о потере нет); N + R + строки «Итог удара» до `/kill` (любой удар залпа до `/kill` —
у коровы или по пути: в v3 единственный такой шахед врезался в постройку в ~170 блоках от коровы) = 30; поздние пуски
(R) — строки «Итог удара» у последней точки коровы (около -272 110 -1142, разброс 50), срок ≤ ~100 с; ни одной
строки о потере цели после появления второй коровы;
`airstrike clear` — «total: 0» (или «0» по-русски); `execute if entity @e[tag=respawned]` — «Test passed»; logscan без
ошибок мода. Прислать `results.txt`.

## C. Видимость ракеты на подлёте (#149)
Сценарий `fx`, режим подлёта: ракета пускается от консоли встроенного сервера (стреляющего нет — заход прямой с севера,
последние 500 блоков); зритель в 40 блоках к востоку от цели, на 35 блоков выше самого высокого столба (крыши, кроны)
в 64 блоках от неё, ставится только над готовыми чанками клиента; режим наблюдателя (в мире игрока — выживание,
и зритель без него падал к цели: v2 сняла кадры `_flight` с земли). Снаряд у клиента ищется по UUID (и заново после ухода
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
Y цели в `fx target … at (x, Y, z)` выше −60 (высоту сценарий берёт только у столбов готовых чанков клиента, столб у дна
мира — «не готов», ждёт; Y ≤ −60 — ошибка задания, не мода: так и написать, D и S2 − S1 этой ракеты не оценивать);
`reached client at tick T1 (server S1) … R from target`, `blast at … (D blocks from target)` с D ≤ 10 и
`impact at tick T2 (server S2)`, S2 − S1 ≥ 36 (тики сервера: R ≈ 190 при скорости ракеты не больше 5 блоков/тик —
от 38; часы клиента догоняют сервер рывками, и T2 − T1 бывало меньше возможного — 32 при R 191), R — около
прорисовки (~190), не 128; нет `no impact` и `skipped`; на кадрах корпус хотя бы в 1 `_flight` или шлейф хотя бы в 3, взрыв есть; нет
ERROR/исключений мода, есть `SCENARIO done`. Прислать строки T1/S1, T2/S2, R и контакт-лист (SendUserFile).

## D. Воронки: осыпание (#147)
(A) одна бомба в насыпную площадку 40×34×40 из камня, песка и гравия; (B) 3 бомбы с разбросом 100 там, где у хоста
30.09 Leaky писал «Detected large amount of entities». Предел живых падающих блоков (`CraterFalls`) — 48 на район
взрыва и 96 на мир; у бомбы два района на одном месте (взрыв входа в грунт и подземный подрыв), поэтому у одной
бомбы предел — 96, как у мира. Сущности района считаются до удара (за 5 с до расчётного попадания), через 10 и 30 с
и в конце окна — отдельно предметы (`type=item`: выпадение из снятых блоков, бомба снимает сотни блоков) и все
остальные, кроме игроков: Leaky видит и сущности самого мира (в v2 — 151 у места (B) за 13 с до удара), поэтому
критерий — рост над замером до удара. Мир — днём (`time set 6000`, смена суток выключена), интерфейс и чат скрыты
(`hud:off`; ответы команд идут в лог, как прежде). Команды — генератором; `F` — `bomber_flight_time` из шага 1
(по умолчанию 40). Сценарий `commands` идёт с `airstrike.commands.gap=1`: после команды — 1 тик (паузы, которые нужны
`fill` и `tp`, генератор ставит сам), после снимка — 20. Падающие блоки считаются раз в 10 тиков, вокруг кадра — через
21 тик (кадр занимает 20):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final/D && F=40 && cat > mod/run/final/D/gen.py <<'EOT'
import sys
F = int(sys.argv[1]) if len(sys.argv) > 1 else 40
GAP = 1  # airstrike.commands.gap: тиков после команды
cmds, t = [], 100
def cmd(c, settle=0):
    global t; cmds.append(c); t += GAP
    if settle > GAP: wait(settle - GAP)
def wait(n):
    global t; cmds.append(f"wait:{n}"); t += n
def shot(name):
    global t; cmds.append(f"shot:{name}"); t += 20
def window(tag, count, area, launch, dense, sparse):
    wait(max(0, launch + (F - 5) * 20 - t))
    ents = f"execute if entity @e[type=!player,type=!item,{area}]"
    items = f"execute if entity @e[type=item,{area}]"
    cmd(ents); cmd(items)                       # до удара: сущности самого мира
    marks = [launch + (F + 10) * 20, launch + (F + 30) * 20]
    end = t + dense * 20; last_shot = t - 60
    while t < end:
        if marks and t >= marks[0]: marks.pop(0); cmd(ents); cmd(items)
        cmd(count)
        if t - last_shot >= 60: last_shot = t; shot(f"crater_{tag}_{(t - launch) // 20:03d}s")
        else: wait(10 - GAP)
    end = t + sparse * 20; last_shot = t
    while t < end:
        if marks and t >= marks[0]: marks.pop(0); cmd(ents); cmd(items)
        cmd(count)
        if t - last_shot >= 100: last_shot = t; shot(f"crater_{tag}_{(t - launch) // 20:03d}s")
        else: wait(20 - GAP)
    cmd(ents); cmd(items)
cmds.append("hud:off"); t += 1
cmd("gamemode spectator", 40); cmd("time set 6000", 40); cmd("gamerule doDaylightCycle false", 40)
cmd("tp @s 2640 225 1051 facing 2600 212 1051", 40); wait(300)
cmd("fill 2580 180 1031 2619 199 1070 stone", 40); cmd("fill 2580 200 1031 2619 209 1070 sand", 40)
cmd("fill 2580 210 1031 2619 213 1070 gravel", 40); wait(40)
launch = t; cmd("airstrike salvo bunker 1 0 at 2600 214 1051", 40)
A = "x=2560,y=-64,z=1011,dx=80,dy=400,dz=80"
window("a", f"execute if entity @e[type=falling_block,{A}]", A, launch, 45, 30)
cmd("tp @s -400 140 -1745 facing -450 60 -1745", 40); wait(300)
launch = t; cmd("airstrike salvo bunker 3 100 at -450 60 -1745", 40)
window("b", "execute if entity @e[type=falling_block]", "x=-590,y=-64,z=-1885,dx=280,dy=400,dz=280", launch, 60, 40)
wait(200)
line = ";".join(cmds)
open("mod/run/final/D/commands.txt", "w").write(line)
print(f"команд {len(cmds)}, {len(line)} символов, тиков сценария {t + 100} (≈{(t + 100) // 20} с)")
EOT
python3 mod/run/final/D/gen.py "$F"
```
Прогон (тайм-аут 30 мин):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && mkdir -p mod/run/final && touch mod/run/final/.step-start && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 25m tools/laptop_job.sh final-crater -- python3 tools/prod_client.py commands --world "Greenfield v0.5.4" --seconds 1200 \
  --prop airstrike.commands.gap=1 --prop "airstrike.commands=$(cat mod/run/final/D/commands.txt)"; echo "код $?"
```
Выжимка (`d.py` — счёт после каждой команды `execute if entity`: строка `SCENARIO /<команда>`, затем ответ
«Test passed, count: N» / «Test failed» = 0; секунды — от строки «Удар» своего окна; строка Leaky — к удару, ближайшему
по месту, если в ней есть координаты, иначе ближайшему по времени, со знаком: «−13с» — до удара):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/final-SHA7" && cd "${W:?}" && L=mod/run/final/D/logs/full.log && cat > mod/run/final/D/d.py <<'EOT'
import re, sys
sys.path.insert(0, "tools"); import logtime
rows = logtime.timeline(logtime.read(sys.argv[1]))
strikes = []  # (время, окно, x, z)
for t, l in rows:
    m = re.search(r"Airstrike/\]: Удар: bunker ×([13]) разброс \d+ по (-?\d+) -?\d+ (-?\d+)", l)
    if m: strikes.append((t, "a" if m[1] == "1" else "b", int(m[2]), int(m[3])))
def window(t):
    w = [s for s in strikes if s[0] <= t + 10]
    return w[-1] if w else None
res = {"a": {"falling": [], "ents": [], "item": []}, "b": {"falling": [], "ents": [], "item": []}}
# ответы сервера идут в порядке команд, но могут прийти после следующей команды (команды — через 1 тик): очередь
asked = []
for t, l in rows:
    m = re.search(r"SCENARIO /execute if entity @e\[type=([^,\]]+)", l)
    if m:
        asked.append({"falling_block": "falling", "!player": "ents", "item": "item"}.get(m[1])); continue
    m = re.search(r"Test passed, count: (\d+)|Проверка пройдена.*?(\d+)\s*$|(Test failed|Проверка не пройдена)", l)
    if m and asked:
        kind = asked.pop(0); n = int(m[1] or m[2] or 0); w = window(t)
        if w and kind: res[w[1]][kind].append((round(t - w[0]), n))
if asked: print(f"!! без ответа осталось команд: {len(asked)}")
def series(r): return " ".join(f"+{s}с:{n}" if s >= 0 else f"{s}с:{n}" for s, n in r)
for t, tag, x, z in strikes: print(f"Удар ({tag}) в {logtime.stamp(t)} по {x} {z}")
for tag in "ab":
    r = res[tag]
    f = r["falling"]
    gaps = [b[0] - a[0] for a, b in zip(f, f[1:])]
    print(f"== ({tag}) падающие блоки: max {max(n for _, n in f) if f else '—'}, отсчётов {len(f)}, самый длинный промежуток "
          f"{max(gaps) if gaps else '—'} с; ненулевые: " + " ".join(f"+{s}с:{n}" for s, n in f if n))
    e = r["ents"]
    if e:
        print(f"== ({tag}) сущности района без предметов: до удара {e[0][1]}; {series(e[1:])}; "
              f"рост: пик {max(n for _, n in e) - e[0][1]}, в конце {e[-1][1] - e[0][1]}")
    i = r["item"]
    if i: print(f"== ({tag}) предметы (для сведения): до удара {i[0][1]}; {series(i[1:])}; пик {max(n for _, n in i)}, в конце {i[-1][1]}")
print("== Leaky (к удару ближайшему по месту, без координат — по времени; секунды от него, «−» — до удара)")
for t, l in rows:
    if "Detected large amount of entities" not in l or not strikes: continue
    m = re.search(r"x=(-?\d+)[^\d-]+?y=(-?\d+)[^\d-]+?z=(-?\d+)", l) or re.search(r"(-?\d+), ?(-?\d+), ?(-?\d+)", l)
    if m:
        x, z = int(m[1]), int(m[3]); s = min(strikes, key=lambda s: (s[2] - x) ** 2 + (s[3] - z) ** 2); how = "месту"
    else:
        s = min(strikes, key=lambda s: abs(s[0] - t)); how = "времени"
    print(f"{logtime.stamp(t)} ({s[1]}, по {how}) {t - s[0]:+.0f}с {logtime.body(l)[:220]}")
EOT
{ python3 mod/run/final/D/d.py "$L"; \
  echo "== ошибки команд"; grep -a -E 'Unknown or incomplete command|do not have permission|That position is not loaded|Неизвестная|Недостаточно прав|не загружен' "$L" | head -5; \
  echo "== logscan"; python3 tools/logscan.py "$L" --all | sed -n '1,/^== Загрузка мода/p' | head -40; } > mod/run/final/D/results.txt; \
ls mod/run/final/D/screenshots/ | grep -c crater_; wc -lc mod/run/final/D/results.txt
```
**Проходит, если:** «Удар: bunker ×1 …» и «Удар: bunker ×3 …»; падающие блоки считались не реже раза в 1,05 с
(«самый длинный промежуток» ≤ 2 с — секунды строк округлены); в (A) хоть раз > 0 и ни разу > 96, в (B) ни разу > 96;
рост сущностей района **без предметов** над замером до удара: пик — в (A) не больше 150, в (B) не больше 300
(падающие блоки, обломки — по 35 на бомбу, живут до приземления), в конце окна — не больше 10; предметы — только
показать (до, пик, в конце); Leaky после удара — только у мест, где он писал и до удара (сущности мира), и с числом
не больше, чем до удара, плюс пик роста с предметами; кадры: день, без чата и интерфейса, на 2 и 5 с у стенок
падающие блоки, на 10 с стенки осели; logscan без ошибок мода.
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
{ grep -a -E 'SCENARIO map-target (selected|order|flight|impact|no impact)|Airstrike/\]: Удар|SCENARIO done' "$L" | cut -c1-400; \
  echo "== logscan"; python3 tools/logscan.py "$L" --all | sed -n '1,/^== Загрузка мода/p' | head -30; } > "mod/run/final/F$N/results.txt"; \
for f in $(ls -t "mod/run/final/F$N/screenshots"/target-map_*.png 2>/dev/null | head -2); do ffmpeg -loglevel error -y -i "$f" -q:v 3 "mod/run/final/F$N/frames/$(basename "${f%.png}").jpg"; done; \
wc -lc "mod/run/final/F$N/results.txt"; ls "mod/run/final/F$N/frames"
```
**Проходит, если:** в 1–2 `distance` > 400 и `order terrain height H` есть (высота с карты в момент приказа — её и увозит
приказ; `selected … terrain height` снята раньше, когда плитка у места ещё могла строиться, — только для сведения);
в 1 Y в `Удар:` = H − 0,5 (H — верх рельефа по карте: у DH это LOD, он бывает ниже крыши на несколько блоков — в v2
у F2 карта 98, крыша 102), не 62–63, взрыв на крыше (Y взрыва в пределах 2 блоков от «земли под взрывом», промах ≤ 2:
сервер уточняет высоту по готовому чанку); в 2 РСЗО рвётся на крыше
(Y в пределах 2; строка `impact … miss M (допуск T) — OK`: допуск РСЗО — 2 блока и три СКО её рассеивания из паспорта,
1 % дальности от пакета у игрока); в 3 Y в `Удар:` не ниже 62 (уровень моря − 1: при пустой карте клиента приказ берёт
первый воздух 63, точка — середина верхнего блока, лог пишет его Y; `order terrain height` там может быть пустым — в v2 было пустым), взрыв на поверхности, `impact … — OK`; logscan без ошибок мода. Прислать строки до `== logscan` из `results.txt` каждого прогона, logscan и 2 кадра каждого.

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
