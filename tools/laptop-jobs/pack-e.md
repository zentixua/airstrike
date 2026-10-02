# Ноутбук: своя сборка — тик A и B на одном мире, B со smoothchunk, пол в повторе (после pack-d на c8e7881)

Тред «Своя сборка», ветка `claude/modpack-research-eujote` (PR #195). Кандидат — один коммит: координатор вписывает
его полный SHA вместо `<SHA>` во **все** блоки ниже перед отправкой (и `SHA7` — первые 7 знаков). Артём в это время
не играет.

**Зачем.** По JFR в `pack-d` лишние ~3 мс тика новой сборки (B) против нынешней (A) — тик чанков (+1,7 мс) и выгрузка
с сохранением чанков (+0,8 мс), волки +0,6. Но миры были разные: сценарий создаёт мир с тем же зерном, а рельеф и биомы
у A меняют её моды (TerraBlender, Atmospheric и др.); спавн мобов в мире сценария выключен, так что тик чанков — это
случайные тики, а животные — из генерации. Здесь A и B идут на **копиях одного и того же мира** (его создаёт B
в `b-gen`), и ещё раз B с модом smoothchunk из A (он растягивает сохранение чанков). Плюс повтор ещё раз: в `pack-d`
пол площадки (гладкий камень) на части кадров повтора был желтоватым — теперь в строках повтора блок пола, который
видит клиент повтора.

Пять запусков по очереди, каждый своей задачей:
- `b-gen` — B, сценарий `salvo-bench` в новом мире; этот мир (после залпов) — общий для трёх следующих;
- `a-same` — A на копии мира `b-gen`, с записью JFR;
- `b-same` — B на копии того же мира, с JFR;
- `b-smooth` — B2 (B + smoothchunk и его настройки из A) на копии того же мира, с JFR;
- `b-replay` — B с модами записи, сценарий `replay`.

## Правила (для каждого блока)
- Каждый блок — **одним вызовом, как написан**; состояние оболочки между вызовами не сохраняется. Блоки с шага 2
  начинаются с `W=… && cd "${W:?}"`.
- Папка — ровно `/mnt/data/projects/airstrike/mod/run/claude-work/pack-e-SHA7` (новая). Уже есть — стоп, сообщить
  координатору, ничего не удалять и не перезаписывать.
- Инстанс Артёма, его миры и настройки только читаются: `prod_client.py --copy-only` копирует инстанс (без миров)
  в `$W/mod/run/ab/A`; B строит `tools/pack_dir.py`; B2 — копия B. Папки прошлых заданий не трогаются.
- Клиент — только через `tools/nested_kwin.sh` (это делает `prod_client.py`); тяжёлое — только `tools/laptop_job.sh`;
  одна задача за раз. `jcmd` — только к java своего запуска (по пути его `launch.args`).
- Остановить задачу — только `systemctl --user stop 'airstrike-job-pack-e-*'`. Никаких `pkill`/`killall`/`kill`
  по имени, `./gradlew --stop`, `rm -rf`, `-f`/`--force`.
- Смерть запуска или «стоп: сценарий не начался» — координатору сразу: последние 40 строк лога и `crash-reports/`.
  Запуск не повторять без слова координатора; остальные запуски по таблице идут дальше.
- Результат — только текст (до ~40 КБ на сообщение) и названные кадры (SendUserFile). Логи, миры, записи JFR
  и каталоги остаются в `$W`; уборку решает Артём словами в треде «Ноутбук».

## 1. Проверка перед запуском
Любой вывод «стоп» — не запускать, сообщить координатору.
```sh
SHA=<SHA>; [ ${#SHA} = 40 ] || echo "стоп: SHA не вписан"
pgrep -x java >/dev/null && echo "стоп: работает java (игра Артёма или другая задача)"
systemctl --user list-units 'airstrike-job-*' --no-legend | grep . && echo "стоп: идёт другая задача"
echo "маунт doc: $(findmnt -n -o ID "/run/user/$(id -u)/doc" || echo "стоп: маунта /run/user/$(id -u)/doc нет")"
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/pack-e-SHA7" && echo "стоп: папка pack-e-SHA7 уже есть"
P="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"; MC="$P/instances/All of Create Aeronautics/minecraft"
ls "$MC/mods" | grep -ciE '\.jar$'; ls "$MC/mods" | grep -i smoothchunk; ls "$MC/config" | grep -i smoothchunk
du -sh "$MC/mods" "$MC/config" "$MC/resourcepacks" "$MC/shaderpacks"; df -h /mnt/data/projects/airstrike/mod/run
```
Свободно — не меньше mods + config + resourcepacks + shaderpacks + 10 ГБ (B2, мир в четырёх копиях, три записи JFR).

## 2. Подготовка: worktree, сборка мода, каталоги A, B и B2
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-e-SHA7" && R=/mnt/data/projects/airstrike && cd "${R:?}" && \
git fetch origin claude/modpack-research-eujote && git worktree add "$W" <SHA> && cd "${W:?}" && mkdir -p "$W/mod/run/ab/out" && \
findmnt -n -o ID "/run/user/$(id -u)/doc" > "$W/mod/run/ab/doc-mount.before" && echo "маунт doc записан" && \
git log --oneline -1 && [ "$(git rev-parse HEAD)" = <SHA> ] && echo "коммит верный" && \
grep -q 'FLOOR' mod/src/devtest/java/ua/zentix/airstrike/scenario/ReplayCheck.java && test -f tools/laptop-jobs/pack-e-jfr.java && echo "инструменты на месте"
```
Должно быть «маунт doc записан», «коммит верный» и «инструменты на месте». Нет — стоп, прислать вывод.

```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-e-SHA7" && cd "${W:?}/mod" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 20m ../tools/laptop_job.sh pack-e-build -- ./gradlew scenarioJar -q --console=plain; echo "код $?"; ls -l build/scenario-libs/
```

A — копия инстанса Артёма (без миров), B — новая сборка с настройками отрисовки из A; в обоих — **maxFps 60**, без
вертикальной синхронизации и без Dynamic FPS.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-e-SHA7" && cd "${W:?}" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout 20m python3 tools/prod_client.py --dir mod/run/ab/A --copy-only && echo "A скопирован: $(ls mod/run/ab/A/mods | grep -c '\.jar$') jar" && \
timeout 20m python3 tools/pack_dir.py mod/run/ab/B && python3 - mod/run/ab/A mod/run/ab/B <<'EOF'
import json, os, shutil, sys
a, b = sys.argv[1:]
shutil.copyfile(os.path.join(a, "options.txt"), os.path.join(b, "options.txt"))
if os.path.isdir(os.path.join(a, "resourcepacks")):
    shutil.copytree(os.path.join(a, "resourcepacks"), os.path.join(b, "resourcepacks"))
for f in ("sodium-options.json", "DistantHorizons.toml"):
    src = os.path.join(a, "config", f)
    print(f, "скопирован" if os.path.isfile(src) else "нет у Артёма")
    if os.path.isfile(src):
        shutil.copyfile(src, os.path.join(b, "config", f))
props = open(os.path.join(a, "config", "iris.properties")).read().splitlines()
pack_a = next((l.split("=", 1)[1] for l in props if l.startswith("shaderPack=")), "")
pack_b = "ComplementaryReimagined_r5.9.3 + EuphoriaPatches_1.10.5" if "Euphoria" in pack_a else "ComplementaryReimagined_r5.9.3.zip"
props = [("shaderPack=" + pack_b) if l.startswith("shaderPack=") else l for l in props]
open(os.path.join(b, "config", "iris.properties"), "w").write("\n".join(props) + "\n")
txt = os.path.join(a, "shaderpacks", pack_a + ".txt")
print(f"шейдер: A {pack_a!r}, B {pack_b!r}; настройки шейдера", "скопированы" if os.path.isfile(txt) else "не найдены")
if os.path.isfile(txt):
    shutil.copyfile(txt, os.path.join(b, "shaderpacks", pack_b + ".txt"))
for d in (a, b):
    opts = os.path.join(d, "options.txt")
    keep = [l for l in open(opts).read().splitlines() if l.split(":", 1)[0] not in ("maxFps", "enableVsync")]
    open(opts, "w").write("\n".join(keep + ["maxFps:60", "enableVsync:false"]) + "\n")
    dyn = os.path.join(d, "config", "dynamic_fps.json")
    cfg = json.load(open(dyn)) if os.path.isfile(dyn) else {}
    cfg["enabled"] = False
    json.dump(cfg, open(dyn, "w"), indent=2)
print("готово")
EOF
for d in A B; do echo "== $d"; grep -E '^(enableShaders|shaderPack)=' "mod/run/ab/$d/config/iris.properties"; grep -E '^(renderDistance|simulationDistance|maxFps|enableVsync|graphicsMode):' "mod/run/ab/$d/options.txt"; done
grep '^configSchemaVersion' mod/run/ab/B/config/aero_cam_sync-client.toml; du -sh mod/run/ab/A mod/run/ab/B
```
Должно кончиться «готово», у A и B — одинаковые прорисовка и шейдер, `maxFps:60`, `configSchemaVersion = 5`.

B2 — копия B плюс jar smoothchunk и его настройки из A. Если smoothchunk требует ещё что-то, кроме NeoForge
и Minecraft, — «пропуск b-smooth» (B2 не создаётся, запуск `b-smooth` не делать):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-e-SHA7" && cd "${W:?}/mod/run/ab" && python3 - <<'EOF'
import glob, os, re, shutil, zipfile
jars = [j for j in glob.glob("A/mods/*.jar") if "smoothchunk" in os.path.basename(j).lower()]
if len(jars) != 1:
    print("пропуск b-smooth: jar smoothchunk в A —", jars or "нет"); raise SystemExit
with zipfile.ZipFile(jars[0]) as z:
    name = next((t for t in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml") if t in z.namelist()), None)
    toml = z.read(name).decode("utf-8", "replace") if name else ""
if not name:
    print("пропуск b-smooth: в jar нет mods.toml"); raise SystemExit
deps = []
for block in re.split(r"(?m)^\s*\[\[dependencies\.", toml)[1:]:
    mod = re.search(r'modId\s*=\s*"([^"]+)"', block)
    kind = re.search(r'type\s*=\s*"([^"]+)"', block)
    req = re.search(r'mandatory\s*=\s*(true|false)', block)
    if mod and ((kind and kind[1].lower() == "required") or (req and req[1] == "true")):
        deps.append(mod[1])
extra = [d for d in deps if d not in ("neoforge", "minecraft")]
print("smoothchunk:", os.path.basename(jars[0]), "обязательные зависимости:", deps)
if extra:
    print("пропуск b-smooth: нужны ещё", extra); raise SystemExit
shutil.copytree("B", "B2", symlinks=True)
shutil.copy2(jars[0], "B2/mods/")
for c in glob.glob("A/config/smoothchunk*"):
    (shutil.copytree if os.path.isdir(c) else shutil.copy2)(c, os.path.join("B2/config", os.path.basename(c)))
    print("настройки:", c)
print("B2 готова:", len([m for m in os.listdir("B2/mods") if m.endswith(".jar")]), "jar")
EOF
du -sh B2 2>/dev/null
```

## 3. Запуски (по одному, каждый — в фоне, тайм-аут вызова 25 мин)
Блок запуска одинаков для всех, меняются только три первых значения: `N`, `D` и `ARGS`. Если через 180 с после
«Game took» в логе нет ни одной строки `SCENARIO`, блок сам останавливает задачу («стоп: сценарий не начался»).
У `a-same`, `b-same`, `b-smooth` блок сам добавляет запись JFR в `out/<N>/rec.jfr` (настройки `profile`, стек до 256
кадров); их мир `--world <N>` — копия из шага «общий мир» ниже, в `<D>/saves/<N>`.

| N | D | ARGS |
|---|---|---|
| `b-gen` | `mod/run/ab/B` | `salvo-bench --prop airstrike.frametimes=true --seconds 1200` |
| `a-same` | `mod/run/ab/A` | `salvo-bench --world a-same --prop airstrike.frametimes=true --seconds 1200` |
| `b-same` | `mod/run/ab/B` | `salvo-bench --world b-same --prop airstrike.frametimes=true --seconds 1200` |
| `b-smooth` | `mod/run/ab/B2` | `salvo-bench --world b-smooth --prop airstrike.frametimes=true --seconds 1200` |
| `b-replay` | `mod/run/ab/B` | `replay --seconds 900` |

Блок запуска (вписать N, D, ARGS из строки таблицы):
```sh
N=b-gen; D=mod/run/ab/B; ARGS="salvo-bench --prop airstrike.frametimes=true --seconds 1200"; \
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-e-SHA7" && cd "${W:?}" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
O="mod/run/ab/out/$N" && L="$D/logs/latest.log" && X="" && \
case "$N" in a-same|b-same|b-smooth) X="--jvm=-XX:StartFlightRecording=filename=$W/$O/rec.jfr,settings=profile,dumponexit=true --jvm=-XX:FlightRecorderOptions=stackdepth=256 --jvm=-XX:+UnlockDiagnosticVMOptions --jvm=-XX:+DebugNonSafepoints"; \
  test -f "$D/saves/$N/level.dat" || { echo "стоп: нет мира $D/saves/$N"; N=""; };; esac; \
if [ -n "$N" ] && mkdir "$O" && test ! -e "$D/logs"; then \
  echo "начало: $(date -u +%T) UTC"; \
  { timeout -k 60 22m tools/laptop_job.sh "pack-e-$N" -- python3 tools/prod_client.py --no-copy --dir "$D" $ARGS $X; echo $? > "$O/code"; } & \
  sleep 20; GO=""; WAITED=0; \
  for i in $(seq 240); do \
    [ -s "$O/code" ] && break; \
    if grep -aq 'Game took' "$L" 2>/dev/null && ! grep -aq 'SCENARIO' "$L"; then WAITED=$((WAITED + 5)); \
      [ "$WAITED" -ge 180 ] && { echo "стоп: сценарий не начался за 180 с после «Game took»"; systemctl --user stop "airstrike-job-pack-e-$N-*"; break; }; fi; \
    case "$N" in b-replay) grep -aq 'SCENARIO' "$L" 2>/dev/null && break;; \
      *) [ "$(grep -ac 'SCENARIO salvo-bench mspt' "$L" 2>/dev/null)" -ge 285 ] && { GO=1; break; };; esac; \
    sleep 5; done; \
  J=""; [ -n "$GO" ] && [ ! -s "$O/code" ] && for p in $(pgrep -f -- "/$D/launch.args"); do [ "$(cat /proc/$p/comm 2>/dev/null)" = java ] && J=$p; done; \
  case "$N" in b-replay) ;; *) if [ -n "$J" ]; then echo "сборка мусора: java $J"; "$JAVA_HOME/bin/jcmd" "$J" GC.run; \
    else echo "сборка мусора: не делаю (нет строки, процесса или запуск кончился)"; fi;; esac; \
  wait; echo "код $(cat "$O/code"), конец: $(date -u +%T) UTC"; case "$(cat "$O/code")" in 124|137) systemctl --user stop "airstrike-job-pack-e-$N-*";; esac; \
  mv "$D/logs" "$O/logs" && for d in crash-reports screenshots; do [ -d "$D/$d" ] && mv "$D/$d" "$O/$d"; done; ls -l "$O"; ls "$O/logs"; \
elif [ -n "$N" ]; then echo "стоп: запуск $N уже был ($O или $D/logs есть)"; fi
```
«стоп: запуск … уже был» или «стоп: нет мира …» — ничего не запускалось, сообщить координатору. Все кончаются кодом 0
и `SCENARIO done`; у `a-same`, `b-same`, `b-smooth` в `ls -l` есть `rec.jfr`.

**Общий мир** — сразу после `b-gen`, до `a-same`: мир `b-gen` (сохранён при выходе) — в `world0` и копиями в A, B, B2:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-e-SHA7" && cd "${W:?}/mod/run/ab" && \
if test -f B/saves/airstrike_scenario/level.dat && test ! -e world0; then cp -a B/saves/airstrike_scenario world0 && \
  echo "world0: $(ls world0/region | wc -l) файлов регионов, $(du -sh world0 | cut -f1)" && \
  for x in A:a-same B:b-same B2:b-smooth; do d=${x%%:*}; n=${x#*:}; \
    if [ ! -d "$d" ]; then echo "нет $d — $n не запускать"; elif [ -e "$d/saves/$n" ]; then echo "стоп: $d/saves/$n уже есть"; \
    else mkdir -p "$d/saves" && cp -a world0 "$d/saves/$n" && echo "мир: $d/saves/$n"; fi; done; \
else echo "стоп: нет мира b-gen (B/saves/airstrike_scenario/level.dat) или world0 уже есть"; fi
```
Регионов меньше 4 — стоп, сообщить координатору.

Перед `b-replay` — один раз включить моды записи, автозапись и сохранение повтора без вопроса (`quicksave`):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-e-SHA7" && cd "${W:?}/mod/run/ab/B" && \
for f in mods/*.jar.disabled; do mv -n "$f" "${f%.disabled}"; done; ls mods | grep -iE 'connector|flashback|forgified'; \
mkdir -p config/flashback && printf '%s\n' '{"configVersion": 2, "recordingControls": {"automaticallyStart": true, "automaticallyFinish": true, "quicksave": true}}' > config/flashback/flashback.json && cat config/flashback/flashback.json
```

## 4. Разбор записей JFR (одним вызовом, после всех запусков)
`tools/laptop-jobs/pack-e-jfr.java` — поток сервера в окне строк `salvo-bench` (280 строк): части тика ванили (спавн,
случайные тики и обход чанков — отдельно), владельцы кода, сущности и блок-сущности по классам, горячие методы;
мс на тик — доля образцов в тике × средний тик из лога; разницы — B−A и B2−B. Плюс паузы сборки мусора и процессор.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-e-SHA7" && cd "${W:?}/mod/run/ab/out" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
R=""; for n in a-same:A b-same:B b-smooth:B2; do f=${n%%:*}; [ -f "$f/rec.jfr" ] && R="$R ${n#*:} $f/logs/latest.log $f/rec.jfr"; done; echo "записи:$R" && \
timeout 20m "$JAVA_HOME/bin/java" -Xmx6g "$W/tools/laptop-jobs/pack-e-jfr.java" $R > jfr.txt 2>&1; \
echo "код $?"; wc -c jfr.txt; head -c 38000 jfr.txt
```

## 5. Сводка (одним вызовом)
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-e-SHA7" && cd "${W:?}/mod/run/ab/out" && python3 - <<'EOF' > summary.txt; cat summary.txt
import collections, os, re, statistics
PHASES = (("до залпа", 0, 29), ("РСЗО", 29, 149), ("шахеды", 149, 280))
def run(n):
    log = open(f"{n}/logs/latest.log", errors="replace").read().splitlines()
    print(f"== {n}: код {open(f'{n}/code').read().strip()}")
    print("запуск:", next((l.split("]: ")[-1] for l in log if "Game took" in l), "строки ModernFix нет"))
    print("шейдер:", "; ".join(sorted({l.split("]: ")[-1][:160] for l in log
                                        if re.search(r"Using shaderpack|Shaders are disabled|Falling back to normal rendering", l)})) or "строк Iris нет")
    gc = open(f"{n}/logs/gc.log", errors="replace").read().splitlines()
    full = [re.search(r"(\d+)M->(\d+)M\((\d+)M\)", l) for l in gc if "Pause Full" in l]  # jcmd GC.run: «Diagnostic Command»
    print("живая куча после jcmd GC.run:", ", ".join(f"{m[2]} МБ (до {m[1]})" for m in full if m) or "нет")
    ft = f"{n}/logs/frametimes.txt"
    if os.path.isfile(ft):
        ms = [float(l.split()[1]) for l in open(ft) if len(l.split()) == 2 and int(l.split()[0]) < 5600]
        if ms:
            q = sorted(ms)
            worst = q[-max(1, len(q) // 100):]
            print(f"кадры: {len(ms)}, средний FPS {1000 / statistics.mean(ms):.1f}, медиана кадра {statistics.median(ms):.1f} мс, "
                  f"1% худших — {1000 / statistics.mean(worst):.1f} FPS, худший кадр {q[-1]:.0f} мс")
    rows = [m for l in log for m in [re.search(r"salvo-bench mspt=([\d.]+) fps=(\d+) entities=(\d+) items=(\d+) chunks=(\d+)", l)] if m][:280]
    if rows:
        ms = [float(m[1]) for m in rows]
        print(f"тик сервера: среднее {statistics.mean(ms):.1f} мс, медиана {statistics.median(ms):.1f}, наибольшее {max(ms):.1f} (по секундам)")
        for phase, a, b in PHASES:
            part = rows[a:b]
            if part:
                col = lambda k: [int(m[k]) for m in part]
                print(f"  {phase}: тик {statistics.mean(float(m[1]) for m in part):.1f} мс (медиана {statistics.median(float(m[1]) for m in part):.1f}), "
                      f"кадров {statistics.mean(col(2)):.0f}/с, сущностей {statistics.mean(col(3)):.0f} (до {max(col(3))}), "
                      f"предметов {statistics.mean(col(4)):.0f} (до {max(col(4))}), чанков {statistics.mean(col(5)):.0f} (до {max(col(5))})")
    errs = collections.Counter(re.sub(r"^\[[^]]*\] \[[^]]*/ERROR\] \[([^]/]*).*", r"\1", l)[:120] for l in log if "/ERROR]" in l)
    print(f"ERROR: {sum(errs.values())} — " + ", ".join(f"{k} {v}" for k, v in errs.most_common(12)))
    bad = [l[:300] for l in log if re.search(r"has crashed|emergencySaveAndCrash|Unreported exception|ua\.zentix.*Exception", l)]
    print("падения:", bad[:5] or "нет")
    print("SCENARIO done:", "есть" if any("SCENARIO done" in l for l in log) else "нет")
    if n == "b-replay":
        rep = [l.split("]: ")[-1][:300] for l in log if "SCENARIO replay" in l]
        play = [r for r in rep if r.startswith("SCENARIO replay play tick=")]
        busy = [r for r in play if "airstrike={}" not in r or " fx=0 " not in r]
        floors = collections.Counter(re.search(r"floor=(\S+)", r)[1] for r in play if re.search(r"floor=(\S+)", r))
        print("повтор:", *[r for r in rep if r not in play], sep="\n  ")
        print(f"проигрывание: строк {len(play)}, со снарядами или эффектами {len(busy)}; блок пола (0 199 60): {dict(floors)}; первые 3 и такие строки (до 40):",
              *(play[:3] + [r for r in busy if r not in play[:3]][:40]), sep="\n  ")
        fb = [l.split("]: ")[-1][:200] for l in log if re.search(r"(?i)flashback", l) and re.search(r"/(ERROR|WARN)\]", l)]
        legacy = [l for l in fb if "Reconstructed legacy replay registry" in l]
        print(f"Flashback: «Reconstructed legacy replay registry» — {len(legacy)} строк; остальные ошибки и предупреждения:",
              *([l for l in fb if l not in legacy][:12] or ["нет"]), sep="\n  ")
for n in ("b-gen", "a-same", "b-same", "b-smooth", "b-replay"):
    if not os.path.isdir(n):
        print(f"== {n}: не запускался"); continue
    try:
        run(n)
    except Exception as e:
        print(f"== {n}: сводка не собралась: {e!r}")
EOF
now=$(findmnt -n -o ID "/run/user/$(id -u)/doc"); [ -n "$now" ] && [ "$now" = "$(cat ../doc-mount.before)" ] && echo "маунт doc цел: $now" || echo "ПРОВАЛ: маунт /run/user/$(id -u)/doc пропал или сменился (был $(cat ../doc-mount.before), стал ${now:-нет})"
```

Кадры повтора (`b-replay`, `replay_*.png`, до 14), JPEG:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-e-SHA7" && cd "${W:?}/mod/run/ab/out" && mkdir -p frames && \
for f in $(ls b-replay/screenshots/replay_*.png 2>/dev/null | head -n 14); do ffmpeg -loglevel error -y -i "$f" -q:v 3 "frames/pack-e-$(basename "$f" .png).jpg"; done; ls -l frames
```

## Что прислать координатору
0. Вывод шагов 1–2 (с блоком B2), «общий мир», код и время каждого запуска.
1. Вывод шага 4 (`out/jfr.txt`, до 38 КБ), `out/summary.txt` целиком и строку о маунте doc.
2. Кадры из `out/frames/` в `/mnt/project-files/modpack/laptop/` (SendUserFile), по строке на кадр.

## Проходит, если
- все запуски кончились сами кодом 0 и `SCENARIO done`; падений нет; «стоп: сценарий не начался» не было
  (`b-smooth` пропущен по зависимостям — не провал);
- `a-same`, `b-same` (и `b-smooth`) открыли копию одного мира `world0`, у каждого есть `rec.jfr`, шаг 4 кончился кодом 0;
- `b-replay`: строки `SCENARIO replay saved`, `opened`, `play from`, строки `play tick=` с `floor=`;
- маунт порталов flatpak цел.
Числа тика и содержимое кадров — для разбора, а не порог.
