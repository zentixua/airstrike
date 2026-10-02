# Ноутбук: кто держит мир после выхода в меню (утечка памяти; одна задача, два прогона)

Тред разбора игры 02.10.2026 (память JVM), ветка `claude/project-thread-ctp5oz`. Кандидат — один коммит: координатор
вписывает его полный SHA вместо `<SHA>` во **все** блоки ниже перед отправкой (и `SHA7` — первые 7 знаков). Артём в это
время не играет.

**Что проверяем.** В игре 02.10 после выхода из мира в меню живых данных кучи на ~2 ГБ больше, чем в меню без мира
(1,2 → 3,2 ГБ), второй мир в том же запуске начинается на ~0,6 ГБ выше, рост идёт и от одних телепортов. Кто держит —
не видно; гипотеза — e4all (`e4all-neoforge-2.1.2.jar`, поставлен в инстанс руками). Мир после выхода должен уходить
из памяти целиком, поэтому замер — в главном меню, и держатель виден за один прогон.

Сценарий `leak` (devtest `LeakCheck`) на копии инстанса **«Airstrike Pack»** (не «All of Create Aeronautics» по
умолчанию) с миром «Newisle v1.3.1»: замер 0 — меню до первого захода; два захода: вход, `gamemode spectator`,
8 телепортов по местам игры через 15 с, выход в меню, как «Сохранить и выйти»; в меню 20 с, две сборки мусора, замер.
Замер: строка `SCENARIO leak menu N: куча живых …`; гистограмма классов живых объектов (`leak/histo-N.txt`, в лог —
ключевые классы мира `ServerLevel`, `ClientLevel`, `IntegratedServer`, `LevelChunk`, `ProtoChunk`, `LevelChunkSection`,
`ChunkHolder`, `Entity` — точное имя и с подклассами — и первые 15 по байтам); выборка старых объектов JFR с путями до
корней GC (`leak/old-N.jfr`, выжимка сэмплов через мир — `leak/old-N.txt`, первые держатели — строками `… holder` в лог).
Два прогона: **A** — копия как есть (с e4all), после второго замера ещё снимок кучи живых объектов `leak/heap.hprof`
(если на диске свободно ≥ 25 ГБ); **B** — та же копия без e4all (jar перенесён из копии в `$W/removed-mods/`).

JVM — как у Артёма (`-Xmx12288m -Xms12288m -XX:+UseZGC -XX:+ZGenerational`) и ещё два флага замера:
`-XX:VMThreadStackSize=8192` — пути до корней JFR ищет поток VM на своём стеке, и со стеком по умолчанию (1 МБ) JVM
падает по SIGSEGV без hs_err (облако 02.10: игра — на первом же сбросе выборки); `-XX:FlightRecorderOptions=old-object-queue-size=1024`
— выборка 1024 объекта вместо 256. Без первого флага сценарий не падает, а пишет выборку без путей (строка WARN).

## Правила (для каждого блока)
- Каждый блок — **одним вызовом, как написан**; состояние оболочки между вызовами не сохраняется. Блоки с шага 2
  начинаются с `W=… && cd "${W:?}"`.
- Папка — ровно `/mnt/data/projects/airstrike/mod/run/claude-work/leak-heap-SHA7` (новая). Уже есть — стоп, сообщить
  координатору, ничего не удалять и не перезаписывать.
- Инстанс Артёма «Airstrike Pack», его миры и настройки не трогать: `prod_client.py --copy-only` копирует инстанс и мир
  в `$W/mod/run/leak-a` и `$W/mod/run/leak-b`; e4all переносится **только из копии B**, не удаляется.
- Клиент — только через `tools/nested_kwin.sh` (это делает `prod_client.py`); тяжёлое — только `tools/laptop_job.sh`;
  одна задача за раз: прогоны A и B — по очереди.
- Остановить задачу — только `systemctl --user stop 'airstrike-job-leak-heap-*'`. Никаких `pkill`/`killall`/`kill`
  по имени, `./gradlew --stop`, `rm -rf`, `-f`/`--force`.
- Смерть прогона — координатору в течение 5 мин: последние 40 строк лога (`tail -n 40 "$W/mod/run/leak-<a|b>/logs/latest.log"`)
  и `crash-reports/`, `hs_err*` из `$W/mod/run/leak-<a|b>/`. Прогон не повторять без слова координатора.
- Результат — только текст (до ~40 КБ на сообщение). Логи, гистограммы, `.jfr`, `.hprof` и копии остаются в `$W`;
  уборку решает Артём словами в треде «Ноутбук».

## 1. Проверка перед запуском
Любой вывод «стоп» — не запускать, сообщить координатору.
```sh
SHA=<SHA>; [ ${#SHA} = 40 ] || echo "стоп: SHA не вписан"
pgrep -x java >/dev/null && echo "стоп: работает java (игра Артёма или другая задача)"
systemctl --user list-units 'airstrike-job-*' --no-legend | grep . && echo "стоп: идёт другая задача"
echo "маунт doc: $(findmnt -n -o ID "/run/user/$(id -u)/doc" || echo "стоп: маунта /run/user/$(id -u)/doc нет")"
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/leak-heap-SHA7" && echo "стоп: папка leak-heap-SHA7 уже есть"
MC="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/Airstrike Pack/minecraft"
test -f "$MC/../mmc-pack.json" || echo "стоп: нет инстанса Airstrike Pack ($MC/../mmc-pack.json)"
ls "$MC/saves" | grep -x "Newisle v1.3.1" || echo "стоп: нет мира Newisle v1.3.1"
ls "$MC/mods" | grep -i '^e4all' || echo "стоп: e4all в инстансе нет — сравнивать не с чем"
du -sh "$MC/saves/Newisle v1.3.1" "$MC/mods" "$MC/config" "$MC/shaderpacks" "$MC/resourcepacks" 2>/dev/null; df -h /mnt/data/projects/airstrike/mod/run
```
Свободно — не меньше 2 × (Newisle + mods + config + shaderpacks + resourcepacks) + 30 ГБ (снимок кучи — до размера
живой кучи, ожидается 3–5 ГБ; при < 25 ГБ к началу прогона A он не снимается).

## 2. Подготовка: worktree, две копии инстанса, сборка
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/leak-heap-SHA7" && R=/mnt/data/projects/airstrike && cd "${R:?}" && \
git fetch origin claude/project-thread-ctp5oz && git cat-file -e '<SHA>^{commit}' && git worktree add "$W" <SHA> && cd "${W:?}" && \
mkdir -p "$W/mod/run/leak-out" && findmnt -n -o ID "/run/user/$(id -u)/doc" > "$W/mod/run/leak-out/doc-mount.before" && echo "маунт doc записан" && \
git log --oneline -1 && [ "$(git rev-parse HEAD)" = <SHA> ] && echo "коммит верный" && \
test -f mod/src/devtest/java/ua/zentix/airstrike/scenario/LeakCheck.java && grep -q '"leak".equals' mod/src/devtest/java/ua/zentix/airstrike/scenario/ClientScenario.java && \
echo "сценарий на месте"
```
Должно быть «маунт doc записан», «коммит верный» и «сценарий на месте». Нет — стоп, прислать вывод.

Копии: A — как есть, B — без e4all (jar — в `$W/removed-mods/`, не удаляется):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/leak-heap-SHA7" && cd "${W:?}" && \
export MC_DIR="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/Airstrike Pack/minecraft" && \
python3 tools/prod_client.py --copy-only --dir "$W/mod/run/leak-a" --world "Newisle v1.3.1" && echo "копия A готова" && \
python3 tools/prod_client.py --copy-only --dir "$W/mod/run/leak-b" --world "Newisle v1.3.1" && echo "копия B готова" && \
mkdir -p "$W/removed-mods" && mv -n "$W/mod/run/leak-b/mods"/e4all-*.jar "$W/removed-mods/" && \
echo "A: e4all $(ls "$W/mod/run/leak-a/mods" | grep -ci '^e4all'), файлов в mods $(ls "$W/mod/run/leak-a/mods" | wc -l)" && \
echo "B: e4all $(ls "$W/mod/run/leak-b/mods" | grep -ci '^e4all'), файлов в mods $(ls "$W/mod/run/leak-b/mods" | wc -l)" && \
ls -l "$W/removed-mods" && du -sh "$W/mod/run/leak-a" "$W/mod/run/leak-b"; df -h "$W/mod/run"
```
Должно быть: A — e4all 1, B — e4all 0, файлов в mods у B на один меньше, в `removed-mods` — jar e4all. Иначе — стоп.

Скрипт выжимки JFR (python3 без пакетов; читает вывод `jfr print`, берёт сэмплы последнего сброса — файл сброса 2
содержит и события сброса 1, — чей объект, путь до корня или корень проходят через мир, и печатает держателей по числу
сэмплов — ближайшее к объекту статическое поле и тип под ним, без него — 4 звена пути у корня и корень, — потом по
2 сэмпла на держателя: размер, возраст, корень, цепочка полей, стек выделения; не больше ~38 КБ; проверен на сбросах
сценария в облаке):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/leak-heap-SHA7" && cd "${W:?}" && cat > "$W/mod/run/leak-out/jfr_world.py" <<'EOF'
import re, sys
# выжимка «jfr print --events jdk.OldObjectSample --stack-depth 64 old-2.jfr»: сэмплы последнего сброса, чей объект,
# путь до корня или корень проходят через мир; держатели по числу сэмплов, потом по 2 сэмпла на держателя — до ~38 КБ
MARKS = ("LevelChunk", "ServerLevel", "ClientLevel", "ChunkHolder", "LevelChunkSection", "PalettedContainer", "IntegratedServer")
events, cur, block = [], None, None
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    line = line.rstrip("\n")
    if line == "jdk.OldObjectSample {":
        cur, block = {"object": [], "root": [], "stack": []}, None
    elif cur is None:
        continue
    elif line == "}":
        events.append(cur); cur = None
    elif block:
        if line in ("  ]", "  }"): block = None
        else: cur[block].append(line.strip())
    elif (m := re.match(r"  (\w+) = (.*)$", line)):
        k, v = m.group(1), m.group(2).strip()
        if (k, v) in (("object", "["), ("root", "{"), ("stackTrace", "[")): block = "stack" if k == "stackTrace" else k
        else: cur[k] = v
def when(e):  # «17:35:33.677 (2026-10-02)» → (дата, время); у всех событий одного сброса оно одно
    m = re.match(r"(\S+) \((\S+)\)", e.get("startTime", ""))
    return (m.group(2), m.group(1)) if m else ("", "")
last = max(map(when, events), default=None)
dump = [e for e in events if when(e) == last]
def root(e):
    r = dict(m.groups() for l in e["root"] if (m := re.match(r'(\w+) = "?(.*?)"?$', l)))
    d = r.get("description", "N/A")
    return f'{r.get("system", "?")} / {r.get("type", "?")}' + ("" if d == "N/A" else f" «{d}»") if r else e.get("root", "N/A")
def plain(l):  # номера в массивах и размеры — без чисел, иначе у каждого сэмпла свой держатель
    return re.sub(r"Size: \d+", "Size: N", re.sub(r"\[\d+\]", "[]", l))
def key(e):  # ближайшее статическое поле («… : java.lang.Class Class Name: X») и тип под ним, иначе 4 звена у корня и корень
    o = e["object"]
    for i in range(1, len(o)):
        if "java.lang.Class Class Name: " in o[i]:
            return plain(o[i - 1].split(" : ", 1)[-1].split(" ")[0]) + " ← " + o[i] + " (статическое поле)"
    return " ← ".join(plain(l) for l in o[-4:]) + " | корень " + re.sub(r"\d+", "#", root(e))
world = [e for e in dump if any(m in "\n".join(e["object"]) + root(e) for m in MARKS)]
groups = {}
for e in world: groups.setdefault(key(e), []).append(e)
order = sorted(groups.items(), key=lambda x: -len(x[1]))
out = [f"событий в файле {len(events)}, последнего сброса {len(dump)} ({' '.join(last or ())}), с корнем "
       f"{sum(root(e) != 'N/A' for e in dump)}, через мир {len(world)} ({', '.join(MARKS)}), держателей {len(order)}",
       "", "Держатели, по числу сэмплов:"]
out += [f"  {len(v):4d} × {k}" for k, v in order[:25]]
out += ["", "Сэмплы через мир, по 2 на держателя (путь — от объекта к корню):"]
size, shown = sum(len(s.encode()) + 1 for s in out), 0
for k, evs in order:
    for e in evs[:2]:
        o = e["object"]
        chain = o if len(o) <= 22 else o[:8] + [f"… ещё {len(o) - 22} звеньев"] + o[-14:]
        text = [f"#{shown + 1} {e.get('objectSize', '?')}, возраст {e.get('objectAge', '?')}, корень {root(e)}"] + ["    " + l for l in chain]
        text.append("    стек: " + " ← ".join(e["stack"][:8]))
        n = sum(len(s.encode()) + 1 for s in text)
        if size + n > 38000: break
        out += text; size += n; shown += 1
print("\n".join(out + ([f"… показано {shown} из {len(world)}"] if shown < len(world) else [])))
EOF
python3 -m py_compile "$W/mod/run/leak-out/jfr_world.py" && echo "скрипт выжимки на месте"
```

Сборка jar сценария — отдельно, до прогонов (в фоне, тайм-аут вызова не меньше 25 мин); код не 0 — сообщить:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/leak-heap-SHA7" && cd "${W:?}/mod" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 20m ../tools/laptop_job.sh leak-heap-build -- ./gradlew scenarioJar -q --console=plain; echo "код $?"; ls -l build/scenario-libs/
```
(prod_client.py внутри задачи ещё раз зовёт `scenarioJar` — после этого шага это проверка «всё собрано», секунды.)

## A. Прогон A — с e4all, снимок кучи (в фоне, тайм-аут вызова 50 мин)
Ожидается ~20 мин: запуск сборки, замер 0, два захода по ~5 мин, снимок кучи.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/leak-heap-SHA7" && cd "${W:?}" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
export MC_DIR="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/Airstrike Pack/minecraft" && \
free=$(df --output=avail -BG "$W/mod/run" | tail -n 1 | tr -dc '0-9'); HD=false; [ "${free:-0}" -ge 25 ] && HD=true; \
echo "свободно ${free} ГБ — снимок кучи: $HD; начало: $(date -u +%T) UTC" && touch "$W/mod/run/leak-out/.a-start" && \
timeout -k 60 45m tools/laptop_job.sh leak-heap-a -- python3 tools/prod_client.py leak --no-copy --dir "$W/mod/run/leak-a" --world "Newisle v1.3.1" --seconds 2400 \
  --jvm=-Xmx12288m --jvm=-Xms12288m --jvm=-XX:+UseZGC --jvm=-XX:+ZGenerational --jvm=-XX:VMThreadStackSize=8192 --jvm=-XX:FlightRecorderOptions=old-object-queue-size=1024 \
  --prop airstrike.leak.rounds=2 --prop airstrike.leak.gap=300 --prop airstrike.leak.settle=20 --prop "airstrike.leak.heapdump=$HD" \
  --prop 'airstrike.leak.commands=gamemode spectator;tp @s -513 150 -1178;tp @s -304 150 -113;tp @s 2943 150 -451;tp @s 2758 150 -579;tp @s 2141 150 -259;tp @s 1228 150 -195;tp @s 592 150 -366;tp @s -944 150 -242'; \
code=$?; echo "код $code, конец: $(date -u +%T) UTC"; case "$code" in 124|137) systemctl --user stop 'airstrike-job-leak-heap-*';; esac; true
```
Наблюдатель: в `$W/mod/run/leak-a/logs/latest.log` идут строки `SCENARIO leak …` (первая — `SCENARIO leak start: … JFR
пишет, с путями до корней`), в конце `SCENARIO done`.

## B. Прогон B — без e4all (после A; в фоне, тайм-аут вызова 50 мин)
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/leak-heap-SHA7" && cd "${W:?}" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
export MC_DIR="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/Airstrike Pack/minecraft" && \
test -z "$(ls "$W/mod/run/leak-b/mods" | grep -i '^e4all')" && echo "e4all в копии B нет" && \
echo "начало: $(date -u +%T) UTC" && touch "$W/mod/run/leak-out/.b-start" && \
timeout -k 60 45m tools/laptop_job.sh leak-heap-b -- python3 tools/prod_client.py leak --no-copy --dir "$W/mod/run/leak-b" --world "Newisle v1.3.1" --seconds 2400 \
  --jvm=-Xmx12288m --jvm=-Xms12288m --jvm=-XX:+UseZGC --jvm=-XX:+ZGenerational --jvm=-XX:VMThreadStackSize=8192 --jvm=-XX:FlightRecorderOptions=old-object-queue-size=1024 \
  --prop airstrike.leak.rounds=2 --prop airstrike.leak.gap=300 --prop airstrike.leak.settle=20 --prop airstrike.leak.heapdump=false \
  --prop 'airstrike.leak.commands=gamemode spectator;tp @s -513 150 -1178;tp @s -304 150 -113;tp @s 2943 150 -451;tp @s 2758 150 -579;tp @s 2141 150 -259;tp @s 1228 150 -195;tp @s 592 150 -366;tp @s -944 150 -242'; \
code=$?; echo "код $code, конец: $(date -u +%T) UTC"; case "$code" in 124|137) systemctl --user stop 'airstrike-job-leak-heap-*';; esac; true
```
Нет строки «e4all в копии B нет» (код 1, клиент не запускался) — стоп, сообщить координатору.

## 3. Логи и выжимка (после обоих прогонов, одним вызовом)
`jfr` ищется в JDK рядом (`java-runtime-delta`, JDK Gradle в `~/.gradle/jdks`, системный); нет нигде — выжимка JFR
берётся из `leak/old-2.txt`, которую сценарий пишет сам тем же разбором.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/leak-heap-SHA7" && cd "${W:?}" && O="$W/mod/run/leak-out" && \
J=""; for c in "$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin/jfr" "$HOME"/.gradle/jdks/*/bin/jfr "$(command -v jfr)"; do [ -n "$c" ] && [ -x "$c" ] && { J="$c"; break; }; done; \
echo "jfr: ${J:-нет — выжимка из leak/old-2.txt сценария}"; \
for v in a b; do P="$W/mod/run/leak-$v"; D="$O/$v"; mkdir -p "$D"; \
  G=$(find "$P/logs" -maxdepth 1 -name '*.log.gz' -newer "$O/.$v-start" | sort -V); { for g in $G; do zcat "$g"; done; cat "$P/logs/latest.log"; } > "$D/full.log"; \
  echo "== $v: SCENARIO done — $(grep -a -c 'SCENARIO done' "$D/full.log"), leak failed — $(grep -a -c 'SCENARIO leak failed' "$D/full.log"), падений — $(grep -a -c -E 'has crashed|emergencySaveAndCrash|Unreported exception' "$D/full.log"), hs_err — $(ls "$P" | grep -c '^hs_err')"; \
  grep -a 'SCENARIO leak' "$D/full.log" | grep -av -E 'SCENARIO leak menu [01] top ' | cut -c1-420 > "$D/lines.txt"; wc -lc "$D/lines.txt"; \
  grep -a -i -E 'e4all|relay session|session ticket' "$D/full.log" | cut -c1-300 | tail -n 30 > "$D/e4all.txt"; wc -l "$D/e4all.txt"; \
  ls -l "$P/leak"; \
  python3 tools/logscan.py "$D/full.log" --all > "$D/logscan.txt"; wc -lc "$D/logscan.txt"; \
  if [ -n "$J" ] && [ -f "$P/leak/old-2.jfr" ] && "$J" print --events jdk.OldObjectSample --stack-depth 64 "$P/leak/old-2.jfr" > "$D/old-2.print.txt" \
     && python3 "$O/jfr_world.py" "$D/old-2.print.txt" > "$D/jfr-2.txt"; then echo "jfr-2.txt — из jfr print"; \
  else cp "$P/leak/old-2.txt" "$D/jfr-2.txt" && echo "jfr-2.txt — из old-2.txt сценария"; fi; wc -lc "$D/jfr-2.txt"; \
done; \
now=$(findmnt -n -o ID "/run/user/$(id -u)/doc"); [ -n "$now" ] && [ "$now" = "$(cat "$O/doc-mount.before")" ] && echo "маунт doc цел: $now" || { echo "ПРОВАЛ: маунт /run/user/$(id -u)/doc пропал или сменился (был $(cat "$O/doc-mount.before"), стал ${now:-нет})"; false; }
```

## Что прислать координатору
0. Вывод шагов 1, 2 и 3 (без содержимого файлов), коды и время прогонов A и B, строку «свободно … — снимок кучи».
1. Прогон A — одним сообщением: `mod/run/leak-out/a/lines.txt` целиком (строки `SCENARIO leak …`: замеры `menu 0/1/2`,
   ключевые классы каждого замера, первые 15 по байтам — только у замера 2, `world`, `entered`, `left`, `jfr`, `holder`,
   `heap dump`) и `e4all.txt`.
2. Прогон A — отдельным сообщением: `mod/run/leak-out/a/jfr-2.txt` целиком (до 38 КБ).
3. То же для B (`leak-out/b/lines.txt`, `e4all.txt` и отдельно `jfr-2.txt`).
4. logscan обоих: ошибки, исключения, `Mixin`, исключения со `ua.zentix` в стеке — или «ноль».
5. Пути к оставшимся на ноутбуке файлам: `$W/mod/run/leak-a/leak/` (`histo-0..2.txt`, `old-0..2.jfr`, `old-0..2.txt`,
   `heap.hprof`) и `$W/mod/run/leak-b/leak/` — с размерами из `ls -l` шага 3.

## Как читать
- `menu 0` — меню того же запуска до первого мира (база), `menu 1`, `menu 2` — после выходов. Мир ушёл из памяти, если
  в `menu 1/2` у `ServerLevel`, `ClientLevel`, `IntegratedServer`, `LevelChunk`, `ProtoChunk`, `LevelChunkSection`,
  `ChunkHolder` и у `Entity` с подклассами — 0 шт., а «куча живых» — в пределах ~0,2 ГБ от `menu 0`.
- Держатель — в строках `holder` и в `jfr-2.txt`: «Тип ← поле : java.lang.Class Class Name: X (статическое поле)» —
  статическое поле класса X держит объект этого типа; без статического поля — последние звенья пути у корня и корень
  (например, локальная переменная потока). Известный держатель мода — `FlightTracks.world` (облако 02.10:
  `ClientLevel ← world : … FlightTracks` — старый клиентский мир со всеми чанками и сущностями живёт в меню до первого
  тика следующего мира); если коммит его уже чинит, его в выжимке быть не должно.
- A и B: в B нет того, что есть в A (`ServerLevel`, `IntegratedServer` и чанки сервера, держатель — классы e4all) —
  держит e4all. Одинаково в обоих — держит не e4all, держатель — в выжимке.
- Мир в сеть не открывается (`/publish` нет: копия мира не должна быть доступна из интернета через ретранслятор). Если
  в `e4all.txt` у A нет строк о сессии ретранслятора, e4all её не заводил, и прогон A показывает только то, что e4all
  держит без сессии; в игре 02.10 сессия была у первого мира.

## Проходит, если
- оба прогона кончаются сами с **кодом 0**, в обоих `SCENARIO done` — 1, `leak failed` — 0, падений и `hs_err` — 0;
- в `lines.txt` обоих: `SCENARIO leak start: … JFR пишет, с путями до корней`, замеры `menu 0`, `menu 1`, `menu 2`,
  `round 1 entered`, `round 2 entered`, по 9 команд в каждом заходе (`round N /…`), два `left world`, у замеров 1 и 2
  строка `jfr: сэмплов …`; у A — строка `heap dump`, если в начале прогона было «снимок кучи: true»;
- маунт порталов flatpak цел: шаг 3 пишет «маунт doc цел» с тем же ID, что в шаге 2; «ПРОВАЛ» — не прошло.
