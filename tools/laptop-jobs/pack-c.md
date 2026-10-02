# Ноутбук: своя сборка — тик сервера при равных кадрах и повтор Flashback (после pack-b на ebe7fad)

Тред «Своя сборка», ветка `claude/modpack-research-eujote` (PR #195). Кандидат — один коммит: координатор вписывает
его полный SHA вместо `<SHA>` во **все** блоки ниже перед отправкой (и `SHA7` — первые 7 знаков). Артём в это время
не играет.

**Зачем.** В `pack-ab`/`pack-b` новая сборка (B) быстрее нынешней (A) по запуску, памяти и кадрам, но тик сервера
у неё выше: 13,5 мс против 9,7 в среднем, по одному прогону. Кандидаты: процессор делится с клиентом (B рисовала
на 39 % кадров больше), моды A, меняющие работу сервера, которых нет в B (smoothchunk, Leaky), разброс. Здесь оба
замера заново при **maxFps 60** (клиент грузит одинаково), и в строке тика раз в секунду — сущности, предметы и
чанки сервера. Плюс тик по фазам из уже снятых логов. И повтор Flashback целиком: запись, выход в меню, повтор
в zip (`quicksave`), открытие повтора, кадры в нём.

Три запуска по очереди, каждый своей задачей:
- `a-bench60` — копия инстанса Артёма (A), сценарий `salvo-bench` при maxFps 60;
- `b-bench60` — новая сборка (B), то же;
- `b-replay` — B с модами записи и автозаписью, `quicksave`; сценарий `replay`: пуск как в `launch`, выход в меню,
  повтор открывается, три кадра повтора (`replay_*.png`).

## Правила (для каждого блока)
- Каждый блок — **одним вызовом, как написан**; состояние оболочки между вызовами не сохраняется. Блоки с шага 3
  начинаются с `W=… && cd "${W:?}"`.
- Папка — ровно `/mnt/data/projects/airstrike/mod/run/claude-work/pack-c-SHA7` (новая). Уже есть — стоп, сообщить
  координатору, ничего не удалять и не перезаписывать.
- Инстанс Артёма, его миры и настройки только читаются: `prod_client.py --copy-only` копирует инстанс (без миров)
  в `$W/mod/run/ab/A`; B строит `tools/pack_dir.py`. `pack-ab-d77c6bb` и `pack-b-ebe7fad` только читаются.
- Клиент — только через `tools/nested_kwin.sh` (это делает `prod_client.py`); тяжёлое — только `tools/laptop_job.sh`;
  одна задача за раз. `jcmd` — только к java своего запуска (по пути его `launch.args`).
- Остановить задачу — только `systemctl --user stop 'airstrike-job-pack-c-*'`. Никаких `pkill`/`killall`/`kill`
  по имени, `./gradlew --stop`, `rm -rf`, `-f`/`--force`.
- Смерть запуска или «стоп: сценарий не начался» — координатору сразу: последние 40 строк лога и `crash-reports/`.
  Запуск не повторять без слова координатора.
- Результат — только текст (до ~40 КБ на сообщение) и названные кадры (SendUserFile). Логи и каталоги остаются
  в `$W`; уборку решает Артём словами в треде «Ноутбук».

## 1. Проверка перед запуском
Любой вывод «стоп» — не запускать, сообщить координатору.
```sh
SHA=<SHA>; [ ${#SHA} = 40 ] || echo "стоп: SHA не вписан"
pgrep -x java >/dev/null && echo "стоп: работает java (игра Артёма или другая задача)"
systemctl --user list-units 'airstrike-job-*' --no-legend | grep . && echo "стоп: идёт другая задача"
echo "маунт doc: $(findmnt -n -o ID "/run/user/$(id -u)/doc" || echo "стоп: маунта /run/user/$(id -u)/doc нет")"
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/pack-c-SHA7" && echo "стоп: папка pack-c-SHA7 уже есть"
C=/mnt/data/projects/airstrike/mod/run/claude-work
for f in pack-ab-d77c6bb/mod/run/ab/out/a-bench/logs/latest.log pack-b-ebe7fad/mod/run/ab/out/b-bench/logs/latest.log; do test -f "$C/$f" || echo "нет $C/$f (фазы из старых логов — без него)"; done
P="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"; MC="$P/instances/All of Create Aeronautics/minecraft"
ls "$MC/mods" | grep -ciE '\.jar$'; grep -E '^(enableShaders|shaderPack)=' "$MC/config/iris.properties"
grep -E '^(renderDistance|simulationDistance|maxFps|enableVsync|graphicsMode):' "$MC/options.txt"
du -sh "$MC/mods" "$MC/config" "$MC/resourcepacks" "$MC/shaderpacks"; df -h /mnt/data/projects/airstrike/mod/run
```
Свободно — не меньше mods + config + resourcepacks + shaderpacks + 5 ГБ.

## 2. Тик по фазам из уже снятых логов (только чтение)
Строка тика — раз в 20 тиков с тика 20; РСЗО — на тике 600, шахеды — на 3000, сводка — до 5600.
```sh
python3 - <<'EOF'
import re, statistics
C = "/mnt/data/projects/airstrike/mod/run/claude-work"
for name, log in (("a-bench (pack-ab)", f"{C}/pack-ab-d77c6bb/mod/run/ab/out/a-bench/logs/latest.log"),
                  ("b-bench (pack-b)", f"{C}/pack-b-ebe7fad/mod/run/ab/out/b-bench/logs/latest.log")):
    try:
        ms = [float(m[1]) for l in open(log, errors="replace") for m in [re.search(r"salvo-bench mspt=([\d.]+)", l)] if m]
    except OSError as e:
        print(name, "нет лога:", e); continue
    for phase, a, b in (("до залпа (20–580)", 0, 29), ("РСЗО (600–2980)", 29, 149), ("шахеды (3000–5600)", 149, 280)):
        part = ms[a:b]
        if part:
            print(f"{name} {phase}: среднее {statistics.mean(part):.1f}, медиана {statistics.median(part):.1f}, наибольшее {max(part):.1f} мс ({len(part)} с)")
EOF
```

## 3. Подготовка: worktree, сборка мода, каталоги A и B
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-c-SHA7" && R=/mnt/data/projects/airstrike && cd "${R:?}" && \
git fetch origin claude/modpack-research-eujote && git worktree add "$W" <SHA> && cd "${W:?}" && mkdir -p "$W/mod/run/ab/out" && \
findmnt -n -o ID "/run/user/$(id -u)/doc" > "$W/mod/run/ab/doc-mount.before" && echo "маунт doc записан" && \
git log --oneline -1 && [ "$(git rev-parse HEAD)" = <SHA> ] && echo "коммит верный" && \
test -f mod/src/devtest/java/ua/zentix/airstrike/scenario/ReplayCheck.java && grep -q 'items={}' mod/src/devtest/java/ua/zentix/airstrike/scenario/ClientScenario.java && echo "инструменты на месте"
```
Должно быть «маунт doc записан», «коммит верный» и «инструменты на месте». Нет — стоп, прислать вывод.

```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-c-SHA7" && cd "${W:?}/mod" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 20m ../tools/laptop_job.sh pack-c-build -- ./gradlew scenarioJar -q --console=plain; echo "код $?"; ls -l build/scenario-libs/
```

A — копия инстанса Артёма (без миров), B — новая сборка с настройками отрисовки из A; в обоих — **maxFps 60**, без
вертикальной синхронизации и без Dynamic FPS.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-c-SHA7" && cd "${W:?}" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
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

## 4. Запуски (по одному, каждый — в фоне, тайм-аут вызова 25 мин)
Блок запуска одинаков для всех трёх, меняются только три первых значения: `N`, `D` и `ARGS`. Если через 180 с после
«Game took» в логе нет ни одной строки `SCENARIO`, блок сам останавливает задачу («стоп: сценарий не начался»).

| N | D | ARGS |
|---|---|---|
| `a-bench60` | `mod/run/ab/A` | `salvo-bench --prop airstrike.frametimes=true --seconds 1200` |
| `b-bench60` | `mod/run/ab/B` | `salvo-bench --prop airstrike.frametimes=true --seconds 1200` |
| `b-replay` | `mod/run/ab/B` | `replay --seconds 900` |

Перед `b-replay` — один раз включить моды записи, автозапись и сохранение повтора без вопроса (`quicksave`):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-c-SHA7" && cd "${W:?}/mod/run/ab/B" && \
for f in mods/*.jar.disabled; do mv -n "$f" "${f%.disabled}"; done; ls mods | grep -iE 'connector|flashback|forgified'; \
mkdir -p config/flashback && printf '%s\n' '{"configVersion": 2, "recordingControls": {"automaticallyStart": true, "automaticallyFinish": true, "quicksave": true}}' > config/flashback/flashback.json && cat config/flashback/flashback.json
```

Блок запуска (вписать N, D, ARGS из строки таблицы):
```sh
N=a-bench60; D=mod/run/ab/A; ARGS="salvo-bench --prop airstrike.frametimes=true --seconds 1200"; \
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-c-SHA7" && cd "${W:?}" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
O="mod/run/ab/out/$N" && L="$D/logs/latest.log" && \
if mkdir "$O" && test ! -e "$D/logs"; then \
  echo "начало: $(date -u +%T) UTC"; \
  { timeout -k 60 22m tools/laptop_job.sh "pack-c-$N" -- python3 tools/prod_client.py --no-copy --dir "$D" $ARGS; echo $? > "$O/code"; } & \
  sleep 20; GO=""; WAITED=0; \
  for i in $(seq 240); do \
    [ -s "$O/code" ] && break; \
    if grep -aq 'Game took' "$L" 2>/dev/null && ! grep -aq 'SCENARIO' "$L"; then WAITED=$((WAITED + 5)); \
      [ "$WAITED" -ge 180 ] && { echo "стоп: сценарий не начался за 180 с после «Game took»"; systemctl --user stop "airstrike-job-pack-c-$N-*"; break; }; fi; \
    case "$N" in *-bench*) [ "$(grep -ac 'SCENARIO salvo-bench mspt' "$L" 2>/dev/null)" -ge 285 ] && { GO=1; break; };; \
      *) grep -aq 'SCENARIO' "$L" 2>/dev/null && break;; esac; \
    sleep 5; done; \
  J=""; [ -n "$GO" ] && [ ! -s "$O/code" ] && for p in $(pgrep -f -- "/$D/launch.args"); do [ "$(cat /proc/$p/comm 2>/dev/null)" = java ] && J=$p; done; \
  case "$N" in *-bench*) if [ -n "$J" ]; then echo "сборка мусора: java $J"; "$JAVA_HOME/bin/jcmd" "$J" GC.run; \
    else echo "сборка мусора: не делаю (нет строки, процесса или запуск кончился)"; fi;; esac; \
  wait; echo "код $(cat "$O/code"), конец: $(date -u +%T) UTC"; case "$(cat "$O/code")" in 124|137) systemctl --user stop "airstrike-job-pack-c-$N-*";; esac; \
  mv "$D/logs" "$O/logs" && for d in crash-reports screenshots; do [ -d "$D/$d" ] && mv "$D/$d" "$O/$d"; done; ls "$O" "$O/logs"; \
else echo "стоп: запуск $N уже был ($O или $D/logs есть)"; fi
```
«стоп: запуск … уже был» — ничего не запускалось, сообщить координатору. Все три кончаются кодом 0 и `SCENARIO done`.

Сразу после `b-replay` — что лежит у Flashback (повтор — `flashback/replays/*.zip`):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-c-SHA7" && cd "${W:?}/mod/run/ab/B" && \
find flashback -maxdepth 3 -printf '%TT %10s %p\n' 2>/dev/null | sort | tail -n 20 || echo "папки flashback нет"; \
for z in flashback/replays/*.zip; do [ -f "$z" ] && python3 -c 'import sys, zipfile; z = zipfile.ZipFile(sys.argv[1]); print(sys.argv[1], [(i.filename, i.file_size) for i in z.infolist()][:12])' "$z"; done
```

## 5. Сводка (одним вызовом, после всех запусков)
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-c-SHA7" && cd "${W:?}/mod/run/ab/out" && python3 - <<'EOF' > summary.txt; cat summary.txt
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
    pauses = [float(m[1]) for l in gc for m in [re.search(r"Pause .* (\d+\.\d+)ms$", l)] if m]
    if pauses:
        print(f"паузы GC: {len(pauses)}, сумма {sum(pauses):.0f} мс, самая долгая {max(pauses):.0f} мс")
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
    errs = collections.Counter(re.sub(r"^\[[^]]*\] \[[^]]*/ERROR\] \[([^]/]*).*", r"\1", l) for l in log if "/ERROR]" in l)
    print(f"ERROR: {sum(errs.values())} — " + ", ".join(f"{k} {v}" for k, v in errs.most_common(12)))
    bad = [l[:300] for l in log if re.search(r"has crashed|emergencySaveAndCrash|Unreported exception|ua\.zentix.*Exception", l)]
    print("падения:", bad[:5] or "нет")
    print("SCENARIO done:", "есть" if any("SCENARIO done" in l for l in log) else "нет")
    if n == "b-replay":
        print("повтор:", *[l.split("]: ")[-1][:300] for l in log if "SCENARIO replay" in l], sep="\n  ")
        fb = [l.split("]: ")[-1][:200] for l in log if re.search(r"(?i)flashback", l) and re.search(r"/(ERROR|WARN)\]", l)]
        print("Flashback, ошибки и предупреждения:", *(fb[:15] or ["нет"]), sep="\n  ")
for n in ("a-bench60", "b-bench60", "b-replay"):
    if not os.path.isdir(n):
        print(f"== {n}: не запускался"); continue
    try:
        run(n)
    except Exception as e:
        print(f"== {n}: сводка не собралась: {e!r}")
EOF
now=$(findmnt -n -o ID "/run/user/$(id -u)/doc"); [ -n "$now" ] && [ "$now" = "$(cat ../doc-mount.before)" ] && echo "маунт doc цел: $now" || echo "ПРОВАЛ: маунт /run/user/$(id -u)/doc пропал или сменился (был $(cat ../doc-mount.before), стал ${now:-нет})"
```

Кадры повтора (`b-replay`, `replay_*.png`), JPEG, не больше трёх:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-c-SHA7" && cd "${W:?}/mod/run/ab/out" && mkdir -p frames && \
for f in $(ls b-replay/screenshots/replay_*.png 2>/dev/null | head -n 3); do ffmpeg -loglevel error -y -i "$f" -q:v 3 "frames/pack-c-$(basename "$f" .png).jpg"; done; ls -l frames
```

## Что прислать координатору
0. Вывод шагов 1–3, код и время каждого запуска, вывод блока Flashback после `b-replay`.
1. Вывод шага 2 (фазы из старых логов), `out/summary.txt` целиком и строку о маунте doc.
2. Кадры из `out/frames/` в `/mnt/project-files/modpack/laptop/` (SendUserFile), по строке на кадр.

## Проходит, если
- все три кончились сами кодом 0 и `SCENARIO done`; падений нет; «стоп: сценарий не начался» не было;
- у `a-bench60` и `b-bench60` один шейдер, maxFps 60 (средний FPS не выше ~60), строки тика с сущностями;
- `b-replay`: строки `SCENARIO replay saved` (zip в `flashback/replays`) и `SCENARIO replay opened`, три кадра повтора;
- маунт порталов flatpak цел.
Числа тика — для разбора, а не порог: проходом считается полный набор.
