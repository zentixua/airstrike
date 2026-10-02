# Ноутбук: новая сборка — залп и запись Flashback заново (после pack-ab на d77c6bb)

Тред «Своя сборка», ветка `claude/modpack-research-eujote` (PR #195). Кандидат — один коммит: координатор вписывает
его полный SHA вместо `<SHA>` во **все** блоки ниже перед отправкой (и `SHA7` — первые 7 знаков). Артём в это время
не играет.

**Почему заново.** В `pack-ab` на d77c6bb запуски `b-bench` и `b-rec` простояли в меню весь срок: Aeronautics Camera
Sync 1.4.0 на первом запуске пишет свой файл настроек с `configSchemaVersion = 0`, а со второго запуска вместо главного
меню открывает вопрос «Config Reset» (ждёт нажатия), и сценарий, который начинается с главного меню, не начинался.
Воспроизведено в облаке. Теперь сборка кладёт `config/aero_cam_sync-client.toml` со схемой 5 — как после нажатия.
Числа `a-menu`, `a-bench`, `b-menu` из `pack-ab-d77c6bb` остаются в силе; каталог A оттуда только читается (настройки
отрисовки для B).

Два запуска по очереди, каждый своей задачей, в **новом** каталоге B (собран из сборки на `<SHA>`):
- `b-bench` — первый запуск каталога: сценарий `salvo-bench` (новый мир, 30 РСЗО и 30 шахедов в 500 блоках, кадры
  в `logs/frametimes.txt`, тик сервера раз в секунду; на тике ~5700 — `jcmd GC.run`: живая куча после залпов);
- `b-rec` — второй запуск (тот случай, где раньше был вопрос): моды записи включены, автозапись Flashback, сценарий
  `launch`: моды записи грузятся, запись идёт, файл повтора появляется.

## Правила (для каждого блока)
- Каждый блок — **одним вызовом, как написан**; состояние оболочки между вызовами не сохраняется. Блоки с шага 2
  начинаются с `W=… && cd "${W:?}"`.
- Папка — ровно `/mnt/data/projects/airstrike/mod/run/claude-work/pack-b-SHA7` (новая). Уже есть — стоп, сообщить
  координатору, ничего не удалять и не перезаписывать.
- Инстанс Артёма не трогается вовсе; `pack-ab-d77c6bb` только читается (A — источник настроек, `out` — прежние
  числа для общей сводки).
- Клиент — только через `tools/nested_kwin.sh` (это делает `prod_client.py`); тяжёлое — только `tools/laptop_job.sh`;
  одна задача за раз. `jcmd` — только к java своего запуска (по пути его `launch.args`).
- Остановить задачу — только `systemctl --user stop 'airstrike-job-pack-b-*'`. Никаких `pkill`/`killall`/`kill`
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
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/pack-b-SHA7" && echo "стоп: папка pack-b-SHA7 уже есть"
OLD=/mnt/data/projects/airstrike/mod/run/claude-work/pack-ab-d77c6bb/mod/run/ab
for f in A/options.txt A/config/iris.properties out/a-bench/logs/latest.log out/b-menu/logs/latest.log; do test -f "$OLD/$f" || echo "стоп: нет $OLD/$f"; done
grep -E '^(enableShaders|shaderPack)=' "$OLD/A/config/iris.properties"; grep -E '^(renderDistance|simulationDistance|maxFps|enableVsync|graphicsMode):' "$OLD/A/options.txt"
df -h /mnt/data/projects/airstrike/mod/run
```
Свободно — не меньше 5 ГБ.

## 2. Подготовка: worktree, сборка мода, каталог B
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-b-SHA7" && R=/mnt/data/projects/airstrike && cd "${R:?}" && \
git fetch origin claude/modpack-research-eujote && git worktree add "$W" <SHA> && cd "${W:?}" && mkdir -p "$W/mod/run/ab/out" && \
findmnt -n -o ID "/run/user/$(id -u)/doc" > "$W/mod/run/ab/doc-mount.before" && echo "маунт doc записан" && \
git log --oneline -1 && [ "$(git rev-parse HEAD)" = <SHA> ] && echo "коммит верный" && \
grep -q '^configSchemaVersion = 5$' pack/config/aero_cam_sync-client.toml && echo "настройки Camera Sync в сборке"
```
Должно быть «маунт doc записан», «коммит верный» и «настройки Camera Sync в сборке». Нет — стоп, прислать вывод.

```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-b-SHA7" && cd "${W:?}/mod" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 20m ../tools/laptop_job.sh pack-b-build -- ./gradlew scenarioJar -q --console=plain; echo "код $?"; ls -l build/scenario-libs/
```

Каталог B — новая сборка с настройками отрисовки из прежнего A (как в `pack-ab`), без ограничения кадров и без
Dynamic FPS. Моды из кэша `mod/run/pack-cache` нового worktree качаются заново (сверка хешей — та же).
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-b-SHA7" && cd "${W:?}" && \
timeout 20m python3 tools/pack_dir.py mod/run/ab/B && python3 - /mnt/data/projects/airstrike/mod/run/claude-work/pack-ab-d77c6bb/mod/run/ab/A mod/run/ab/B <<'EOF'
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
opts = os.path.join(b, "options.txt")
keep = [l for l in open(opts).read().splitlines() if l.split(":", 1)[0] not in ("maxFps", "enableVsync")]
open(opts, "w").write("\n".join(keep + ["maxFps:260", "enableVsync:false"]) + "\n")
dyn = os.path.join(b, "config", "dynamic_fps.json")
cfg = json.load(open(dyn)) if os.path.isfile(dyn) else {}
cfg["enabled"] = False
json.dump(cfg, open(dyn, "w"), indent=2)
print("готово")
EOF
grep -E '^(enableShaders|shaderPack)=' mod/run/ab/B/config/iris.properties; grep -E '^(renderDistance|simulationDistance|maxFps|enableVsync|graphicsMode):' mod/run/ab/B/options.txt
grep '^configSchemaVersion' mod/run/ab/B/config/aero_cam_sync-client.toml; du -sh mod/run/ab/B
```
Должно кончиться «готово», `configSchemaVersion = 5`, прорисовка и шейдер — как у A в шаге 1.

## 3. Запуски (по одному, каждый — в фоне, тайм-аут вызова 25 мин)
Блок запуска одинаков для обоих, меняются только три первых значения: `N`, `D` и `ARGS`. Если через 180 с после
«Game took» в логе нет ни одной строки `SCENARIO`, блок сам останавливает задачу («стоп: сценарий не начался»).

| N | D | ARGS |
|---|---|---|
| `b-bench` | `mod/run/ab/B` | `salvo-bench --prop airstrike.frametimes=true --seconds 1200` |
| `b-rec` | `mod/run/ab/B` | `launch --seconds 900` |

Перед `b-rec` — один раз включить моды записи и автозапись:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-b-SHA7" && cd "${W:?}/mod/run/ab/B" && \
for f in mods/*.jar.disabled; do mv -n "$f" "${f%.disabled}"; done; ls mods | grep -iE 'connector|flashback|forgified'; \
mkdir -p config/flashback && printf '%s\n' '{"configVersion": 2, "recordingControls": {"automaticallyStart": true, "automaticallyFinish": true}}' > config/flashback/flashback.json && cat config/flashback/flashback.json
```

Блок запуска (вписать N, D, ARGS из строки таблицы):
```sh
N=b-bench; D=mod/run/ab/B; ARGS="salvo-bench --prop airstrike.frametimes=true --seconds 1200"; \
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-b-SHA7" && cd "${W:?}" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
O="mod/run/ab/out/$N" && L="$D/logs/latest.log" && \
if mkdir "$O" && test ! -e "$D/logs"; then \
  echo "начало: $(date -u +%T) UTC"; \
  { timeout -k 60 22m tools/laptop_job.sh "pack-b-$N" -- python3 tools/prod_client.py --no-copy --dir "$D" $ARGS; echo $? > "$O/code"; } & \
  sleep 20; GO=""; WAITED=0; \
  for i in $(seq 240); do \
    [ -s "$O/code" ] && break; \
    if grep -aq 'Game took' "$L" 2>/dev/null && ! grep -aq 'SCENARIO' "$L"; then WAITED=$((WAITED + 5)); \
      [ "$WAITED" -ge 180 ] && { echo "стоп: сценарий не начался за 180 с после «Game took»"; systemctl --user stop "airstrike-job-pack-b-$N-*"; break; }; fi; \
    case "$N" in *-bench) [ "$(grep -ac 'SCENARIO salvo-bench mspt' "$L" 2>/dev/null)" -ge 285 ] && { GO=1; break; };; \
      *) grep -aq 'SCENARIO' "$L" 2>/dev/null && break;; esac; \
    sleep 5; done; \
  J=""; [ -n "$GO" ] && [ ! -s "$O/code" ] && for p in $(pgrep -f -- "/$D/launch.args"); do [ "$(cat /proc/$p/comm 2>/dev/null)" = java ] && J=$p; done; \
  case "$N" in *-bench) if [ -n "$J" ]; then echo "сборка мусора: java $J"; "$JAVA_HOME/bin/jcmd" "$J" GC.run; \
    else echo "сборка мусора: не делаю (нет строки, процесса или запуск кончился)"; fi;; esac; \
  wait; echo "код $(cat "$O/code"), конец: $(date -u +%T) UTC"; case "$(cat "$O/code")" in 124|137) systemctl --user stop "airstrike-job-pack-b-$N-*";; esac; \
  mv "$D/logs" "$O/logs" && for d in crash-reports screenshots; do [ -d "$D/$d" ] && mv "$D/$d" "$O/$d"; done; ls "$O" "$O/logs"; \
else echo "стоп: запуск $N уже был ($O или $D/logs есть)"; fi
```
«стоп: запуск … уже был» — ничего не запускалось, сообщить координатору. Оба кончаются кодом 0 и `SCENARIO done`.

После `b-rec` — что записал Flashback:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-b-SHA7" && cd "${W:?}/mod/run/ab/B" && \
find flashback -maxdepth 3 -printf '%TT %10s %p\n' 2>/dev/null | sort | tail -n 20 || echo "папки flashback нет"
```

## 4. Сводка (одним вызовом, после обоих запусков)
Сводка — все пять запусков: `a-menu`, `a-bench`, `b-menu` из `pack-ab-d77c6bb` (только чтение), `b-bench`, `b-rec`
отсюда.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-b-SHA7" && cd "${W:?}/mod/run/ab/out" && python3 - <<'EOF' > summary.txt; cat summary.txt
import collections, os, re, statistics
OLD = "/mnt/data/projects/airstrike/mod/run/claude-work/pack-ab-d77c6bb/mod/run/ab/out"
TS = re.compile(r"^\[(\d\d)\w+(\d{4}) (\d\d):(\d\d):(\d\d)\.(\d+)\]")
def secs(line):
    m = TS.match(line)
    return int(m[3]) * 3600 + int(m[4]) * 60 + int(m[5]) + int(m[6]) / 1000 if m else None
def run(n, base):
    d = os.path.join(base, n)
    log = open(f"{d}/logs/latest.log", errors="replace").read().splitlines()
    t0 = next(s for s in map(secs, log) if s is not None)
    def at(pat):
        for l in log:
            if re.search(pat, l):
                return secs(l) - t0
    print(f"== {n} ({'pack-ab-d77c6bb' if base == OLD else 'этот прогон'}): код {open(f'{d}/code').read().strip()}")
    took = next((l.split("]: ")[-1] for l in log if "Game took" in l), "строки ModernFix нет")
    print(f"запуск: {took}; до создания мира {at(r'Starting integrated minecraft server')} с от первой строки лога")
    print("шейдер:", "; ".join(sorted({l.split("]: ")[-1][:160] for l in log
                                        if re.search(r"Using shaderpack|Shaders are disabled|Falling back to normal rendering", l)})) or "строк Iris нет")
    gc = open(f"{d}/logs/gc.log", errors="replace").read().splitlines()
    full = [re.search(r"(\d+)M->(\d+)M\((\d+)M\)", l) for l in gc if "Pause Full" in l]  # jcmd GC.run: «Diagnostic Command»
    print("живая куча после jcmd GC.run:", ", ".join(f"{m[2]} МБ (до {m[1]})" for m in full if m) or "нет")
    pauses = [float(m[1]) for l in gc for m in [re.search(r"Pause .* (\d+\.\d+)ms$", l)] if m]
    peak = max((int(m[1]) for l in gc for m in [re.search(r"(\d+)M->(\d+)M", l)] if m), default=0)
    if pauses:
        print(f"паузы GC: {len(pauses)}, сумма {sum(pauses):.0f} мс, самая долгая {max(pauses):.0f} мс; наибольшая куча перед сборкой {peak} МБ")
    ft = f"{d}/logs/frametimes.txt"
    if os.path.isfile(ft):
        ms = [float(l.split()[1]) for l in open(ft) if len(l.split()) == 2 and int(l.split()[0]) < 5600]
        if ms:
            q = sorted(ms)
            worst = q[-max(1, len(q) // 100):]
            print(f"кадры: {len(ms)}, средний FPS {1000 / statistics.mean(ms):.1f}, медиана кадра {statistics.median(ms):.1f} мс, "
                  f"1% худших — {1000 / statistics.mean(worst):.1f} FPS, худший кадр {q[-1]:.0f} мс")
    mspt = [float(m[1]) for l in log for m in [re.search(r"salvo-bench mspt=([\d.]+)", l)] if m][:280]  # до тика 5600
    if mspt:
        print(f"тик сервера: среднее {statistics.mean(mspt):.1f} мс, медиана {statistics.median(mspt):.1f}, наибольшее {max(mspt):.1f} (по секундам)")
    errs = collections.Counter(re.sub(r"^\[[^]]*\] \[[^]]*/ERROR\] \[([^]/]*).*", r"\1", l) for l in log if "/ERROR]" in l)
    print(f"ERROR: {sum(errs.values())} — " + ", ".join(f"{k} {v}" for k, v in errs.most_common(12)))
    bad = [l[:300] for l in log if re.search(r"has crashed|emergencySaveAndCrash|Unreported exception|ua\.zentix.*Exception", l)]
    print("падения:", bad[:5] or "нет")
    print("SCENARIO done:", "есть" if any("SCENARIO done" in l for l in log) else "нет")
    if n == "b-rec":
        keep = [l.split("]: ")[-1][:200] for l in log if re.search(r"(?i)flashback|connector", l) and "/DEBUG]" not in l]
        print("Flashback/Connector:", *keep[:25], sep="\n  ")
for n, base in (("a-menu", OLD), ("a-bench", OLD), ("b-menu", OLD), ("b-bench", "."), ("b-rec", ".")):
    if not os.path.isdir(os.path.join(base, n)):
        print(f"== {n}: не запускался"); continue
    try:
        run(n, base)
    except Exception as e:
        print(f"== {n}: сводка не собралась: {e!r}")
EOF
now=$(findmnt -n -o ID "/run/user/$(id -u)/doc"); [ -n "$now" ] && [ "$now" = "$(cat ../doc-mount.before)" ] && echo "маунт doc цел: $now" || echo "ПРОВАЛ: маунт /run/user/$(id -u)/doc пропал или сменился (был $(cat ../doc-mount.before), стал ${now:-нет})"
```

Кадры `b-rec` (новая сборка под шейдером): JPEG из `out/b-rec/screenshots`, не больше четырёх:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-b-SHA7" && cd "${W:?}/mod/run/ab/out" && mkdir -p frames && \
for f in $(ls b-rec/screenshots/*.png 2>/dev/null | head -n 4); do ffmpeg -loglevel error -y -i "$f" -q:v 3 "frames/$(basename "$f" .png).jpg"; done; ls -l frames
```

## Что прислать координатору
0. Вывод шагов 1 и 2, код и время каждого запуска, список файлов Flashback после `b-rec`.
1. `out/summary.txt` целиком и строку о маунте doc.
2. Кадры из `out/frames/` в `/mnt/project-files/modpack/laptop/` (SendUserFile), по строке на кадр.

## Проходит, если
- `b-bench` и `b-rec` кончились сами кодом 0 и `SCENARIO done`; падений нет; «стоп: сценарий не начался» не было;
- у `b-bench` есть «живая куча после jcmd GC.run»;
- шейдер у `b-bench` тот же, что у `a-bench` (строки «шейдер:»);
- `b-rec`: Connector и Flashback загрузились, в `flashback/` есть файл повтора или папка идущей записи с данными;
- маунт порталов flatpak цел.
