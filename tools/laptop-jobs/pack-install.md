# Ноутбук: сборка «Airstrike Pack» 0.1.0 — отдельным инстансом в Prism у Артёма, проверка запуском до меню

Тред «Своя сборка», ветка `claude/modpack-research-eujote`. Кандидат — один коммит: координатор вписывает его полный
SHA вместо `<SHA>` во **все** блоки ниже перед отправкой (и `SHA7` — первые 7 знаков). Артём в это время не играет.

**Зачем.** Артём просил, чтобы сборка стояла у него и была готова к игре с друзьями. Новый инстанс Prism
«Airstrike Pack» рядом с его «All of Create Aeronautics»: те же файлы, что Prism ставит из `.mrpack` сборки
(`tools/pack_dir.py` — моды по хешам из `pack/`, `config/` сборки), моды записи (Flashback и его три мода) у Артёма
включены. Из его инстанса копируются (только чтение): память, Java и её аргументы (`instance.cfg`; без строки JFR,
её `JvmArgs` — из копии до JFR), версии игры (`mmc-pack.json`), настройки игры (`options.txt`), Distant Horizons,
Sodium, выбор и настройки шейдера — так же, как для B в pack-d. Перед установкой — запуск копии нового инстанса до
меню с его же аргументами Java (5 минут во вложенном KWin) и выжимка лога.

## Правила (для каждого блока)
- Каждый блок — **одним вызовом, как написан**; состояние оболочки между вызовами не сохраняется. Блоки с шага 2
  начинаются с `W=… && cd "${W:?}"`.
- Папка — ровно `/mnt/data/projects/airstrike/mod/run/claude-work/pack-install-SHA7` (новая). Уже есть — стоп,
  сообщить координатору, ничего не удалять и не перезаписывать.
- Инстанс «All of Create Aeronautics», его миры и настройки только читаются. В `instances/` Prism добавляется одна
  новая папка «Airstrike Pack» (шаг 5); если она уже есть — стоп. Другие инстансы и файлы Prism не трогаются.
- Клиент — только через `tools/nested_kwin.sh` (это делает `prod_client.py`); тяжёлое — только `tools/laptop_job.sh`.
- Остановить задачу — только `systemctl --user stop 'airstrike-job-pack-install-*'`. Никаких `pkill`/`killall`/`kill`
  по имени, `rm -rf`, `-f`/`--force`.
- Любой вывод «стоп» или смерть запуска — координатору сразу (последние 40 строк лога и `crash-reports/`), дальше не
  идти и не повторять без его слова.
- Результат — только текст (до ~40 КБ на сообщение). Логи и каталоги остаются в `$W`.

## 1. Проверка перед запуском
```sh
SHA=<SHA>; [ ${#SHA} = 40 ] || echo "стоп: SHA не вписан"
pgrep -x java >/dev/null && echo "стоп: работает java (игра Артёма или другая задача)"
systemctl --user list-units 'airstrike-job-*' --no-legend | grep . && echo "стоп: идёт другая задача"
echo "наблюдатели (смотрят только java старого инстанса):"; systemctl --user list-units 'airstrike-watch-*' --no-legend
echo "маунт doc: $(findmnt -n -o ID "/run/user/$(id -u)/doc" || echo "стоп: маунта /run/user/$(id -u)/doc нет")"
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/pack-install-SHA7" && echo "стоп: папка pack-install-SHA7 уже есть"
P="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"; OLD="$P/instances/All of Create Aeronautics"
test -e "$P/instances/Airstrike Pack" && echo "стоп: инстанс «Airstrike Pack» уже есть"
ls "$P/instances"; pgrep -a -f -i prismlauncher | cut -c1-120 | head -3 || echo "Prism не запущен"
python3 - "$OLD" <<'EOF'
import glob, json, os, sys
old = sys.argv[1]
comp = {c["uid"]: c.get("version") for c in json.load(open(os.path.join(old, "mmc-pack.json")))["components"]}
print("mmc-pack.json:", ", ".join(f"{k} {v}" for k, v in comp.items()))
if comp.get("net.minecraft") != "1.21.1" or comp.get("net.neoforged") != "21.1.250":
    print("стоп: у инстанса не Minecraft 1.21.1 + NeoForge 21.1.250, как в сборке")
if set(comp) - {"org.lwjgl3", "net.minecraft", "net.neoforged"} or os.path.isdir(os.path.join(old, "patches")):
    print("стоп: у инстанса свои компоненты или patches/ — сообщить координатору")
kv = {}
for l in open(os.path.join(old, "instance.cfg"), encoding="utf-8").read().splitlines():
    if "=" in l and not l.startswith("["):
        kv.setdefault(l.split("=", 1)[0], l.split("=", 1)[1])
for k in ("OverrideMemory", "MinMemAlloc", "MaxMemAlloc", "OverrideJavaLocation", "JavaPath", "OverrideJavaArgs"):
    print(f"{k}={kv.get(k, '(нет)')}")
jvm = kv.get("JvmArgs", "")
print("JvmArgs:", jvm[:400])
if "FlightRecord" in jvm:
    backs = glob.glob(os.path.join(old, "minecraft", "airstrike-backup", "instance.cfg.*.before-jfr"))
    print("копия до JFR:", backs or "стоп: нет копии instance.cfg до JFR")
    if len(backs) > 1:
        print("стоп: копий до JFR несколько — сообщить координатору")
EOF
df -h /mnt/data/projects/airstrike/mod/run "$P/instances"
```
Свободно — не меньше 4 ГБ в обоих местах.

## 2. Подготовка: worktree, отпечаток старого инстанса
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-install-SHA7" && R=/mnt/data/projects/airstrike && cd "${R:?}" && \
git fetch origin claude/modpack-research-eujote && git worktree add "$W" <SHA> && cd "${W:?}" && \
findmnt -n -o ID "/run/user/$(id -u)/doc" > "$W/doc-mount.before" && echo "маунт doc записан" && \
git log --oneline -1 && [ "$(git rev-parse HEAD)" = <SHA> ] && echo "коммит верный" && \
OLD="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/All of Create Aeronautics" && \
{ sha256sum "$OLD/instance.cfg" "$OLD/mmc-pack.json" "$OLD/minecraft/options.txt"; ls -l "$OLD/minecraft/mods" | sha256sum; } > "$W/old.before" && \
cat "$W/old.before" && echo "отпечаток старого инстанса записан"
```
Должно быть «маунт doc записан», «коммит верный» и «отпечаток старого инстанса записан».

## 3. Новый инстанс в папке задачи
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-install-SHA7" && cd "${W:?}" && \
OLD="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/All of Create Aeronautics" && S="$W/stage/Airstrike Pack" && \
mkdir -p "$S" && timeout 20m python3 tools/pack_dir.py "$S/minecraft" --optional && python3 - "$OLD" "$S" <<'EOF'
import glob, hashlib, json, os, shutil, sys
old, s = sys.argv[1:]
mc_old, mc = os.path.join(old, "minecraft"), os.path.join(s, "minecraft")
def stop(msg):
    print("стоп:", msg); sys.exit(1)
jar = os.path.join(mc, "mods", "airstrike-2.5.0.jar")
h = hashlib.sha256(open(jar, "rb").read()).hexdigest()
print("airstrike-2.5.0.jar:", os.path.getsize(jar), "байт, sha256", h)
if h != "ce6d503bbb3a27c9d6577f0cea3e5ee0649425c5da1b6767fac50572ef5063d3":
    stop("jar Airstrike не тот, что в выпуске 2.5.0")
# настройки игры Артёма — копии
shutil.copyfile(os.path.join(mc_old, "options.txt"), os.path.join(mc, "options.txt"))
for f in ("DistantHorizons.toml", "sodium-options.json"):
    src = os.path.join(mc_old, "config", f)
    print(f, "скопирован" if os.path.isfile(src) else "нет у Артёма")
    if os.path.isfile(src):
        shutil.copyfile(src, os.path.join(mc, "config", f))
iris = os.path.join(mc_old, "config", "iris.properties")
if os.path.isfile(iris):
    props = open(iris).read().splitlines()
    pack_a = next((l.split("=", 1)[1] for l in props if l.startswith("shaderPack=")), "")
    pack_b = "ComplementaryReimagined_r5.9.3 + EuphoriaPatches_1.10.5" if "Euphoria" in pack_a else "ComplementaryReimagined_r5.9.3.zip"
    props = [("shaderPack=" + pack_b) if l.startswith("shaderPack=") else l for l in props]
    open(os.path.join(mc, "config", "iris.properties"), "w").write("\n".join(props) + "\n")
    txt = os.path.join(mc_old, "shaderpacks", pack_a + ".txt")
    print(f"шейдер: у Артёма {pack_a!r}, в сборке {pack_b!r}; настройки шейдера", "скопированы" if os.path.isfile(txt) else "не найдены")
    if os.path.isfile(txt):
        shutil.copyfile(txt, os.path.join(mc, "shaderpacks", pack_b + ".txt"))
else:
    print("iris.properties у Артёма нет — шейдер выключен, как у друзей")
# instance.cfg — его, кроме имени, значка, времени игры, заметок, данных экспорта и строки JFR
key = lambda l: l.split("=", 1)[0] if "=" in l and not l.startswith("[") else None
cfg = open(os.path.join(old, "instance.cfg"), encoding="utf-8").read().splitlines()
jvm = next((l for l in cfg if key(l) == "JvmArgs"), None)
if jvm and "FlightRecord" in jvm:
    backs = glob.glob(os.path.join(mc_old, "airstrike-backup", "instance.cfg.*.before-jfr"))
    if len(backs) != 1:
        stop("копия instance.cfg до JFR не одна")
    jvm = next((l for l in open(backs[0], encoding="utf-8").read().splitlines() if key(l) == "JvmArgs"), None)
    if jvm is None or "FlightRecord" in jvm:
        stop("в копии до JFR нет JvmArgs без JFR")
    print("JvmArgs — из", os.path.basename(backs[0]))
drop = {"name", "iconKey", "notes", "lastLaunchTime", "lastTimePlayed", "totalTimePlayed", "linkedInstances"}
out = [jvm if key(l) == "JvmArgs" else l for l in cfg
       if not (key(l) in drop or (key(l) or "").startswith(("ManagedPack", "Export")))]
at = out.index("[General]") + 1 if "[General]" in out else 0
out[at:at] = ["name=Airstrike Pack 0.1.0", "iconKey=default", "ManagedPack=false"]
open(os.path.join(s, "instance.cfg"), "w", encoding="utf-8").write("\n".join(out) + "\n")
shutil.copyfile(os.path.join(old, "mmc-pack.json"), os.path.join(s, "mmc-pack.json"))
for l in out:
    if key(l) in ("name", "OverrideMemory", "MinMemAlloc", "MaxMemAlloc", "JavaPath", "OverrideJavaArgs", "JvmArgs"):
        print(l[:400])
print("готово")
EOF
ls "$S" "$S/minecraft"; ls "$S/minecraft/mods" | grep -c '\.jar$'; ls "$S/minecraft/mods" | grep -c '\.disabled$'
grep '^configSchemaVersion' "$S/minecraft/config/aero_cam_sync-client.toml"; du -sh "$S"
```
Должно кончиться «готово», sha256 jar — `ce6d503b…063d3`, модов включено 50, выключенных 0,
`configSchemaVersion = 5`.

## 4. Проверка: копия нового инстанса до меню (в фоне, тайм-аут вызова 15 мин)
Запуск с памятью и аргументами Java нового инстанса, закрывается сам через 300 с (код 143 — так и задумано).
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-install-SHA7" && cd "${W:?}" && \
export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
S="$W/stage/Airstrike Pack" && C="$W/check" && test ! -e "$C" && cp -a "$S/minecraft" "$C" && \
mapfile -t JV < <(python3 - "$S/instance.cfg" <<'EOF'
import shlex, sys
kv = {}
for l in open(sys.argv[1], encoding="utf-8").read().splitlines():
    if "=" in l and not l.startswith("["):
        kv.setdefault(l.split("=", 1)[0], l.split("=", 1)[1])
on = lambda k: kv.get(k, "").lower() == "true"
args = []
if on("OverrideMemory"):
    args += [f"-Xms{kv['MinMemAlloc']}m"] if kv.get("MinMemAlloc") else []
    args += [f"-Xmx{kv['MaxMemAlloc']}m"] if kv.get("MaxMemAlloc") else []
if on("OverrideJavaArgs"):
    v = kv.get("JvmArgs", "").strip()
    if len(v) > 1 and v[0] == v[-1] == '"':
        v = v[1:-1].replace('\\"', '"').replace("\\\\", "\\")
    args += shlex.split(v)
print("\n".join("--jvm=" + a for a in args))
EOF
) && echo "аргументы Java проверки: ${JV[*]}" && echo "начало: $(date -u +%T) UTC" && \
timeout -k 60 12m tools/laptop_job.sh pack-install-check -- python3 tools/prod_client.py --no-copy --dir "$C" \
  --airstrike-jar "$S/minecraft/mods/airstrike-2.5.0.jar" --seconds 300 "${JV[@]}"; \
echo "код $? (143 — закрыт по сроку), конец: $(date -u +%T) UTC"
```

Выжимка (одним вызовом):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-install-SHA7" && cd "${W:?}/check" && python3 - <<'EOF'
import collections, os, re
log = open("logs/latest.log", errors="replace").read().splitlines()
print("запуск:", next((l.split("]: ")[-1] for l in log if "Game took" in l), "НЕТ строки «Game took» — меню не открылось"))
print("Java:", next((l.split("]: ")[-1][:200] for l in log if re.search(r"Java is|JVM identified|java version", l)), "строки нет"))
print("шейдер:", "; ".join(sorted({l.split("]: ")[-1][:160] for l in log
                                    if re.search(r"Using shaderpack|Shaders are disabled", l)})) or "строк Iris нет")
for name in ("Essential", "Flashback", "Distant", "airstrike"):
    print(f"строк с «{name}»:", sum(name.lower() in l.lower() for l in log))
errs = collections.Counter(re.sub(r"^\[[^]]*\] \[[^]]*/ERROR\] \[([^]/]*).*", r"\1", l) for l in log if "/ERROR]" in l)
print(f"ERROR: {sum(errs.values())} — " + ", ".join(f"{k} {v}" for k, v in errs.most_common(12)))
bad = [l[:300] for l in log if re.search(r"has crashed|Unreported exception|ua\.zentix.*Exception|Missing or unsupported mandatory", l)]
print("падения:", bad[:5] or "нет")
print("crash-reports:", os.listdir("crash-reports") if os.path.isdir("crash-reports") else "нет")
EOF
tail -n 5 logs/latest.log | cut -c1-250
```
Годится, если есть «Game took», падений и `crash-reports` нет. Иначе — стоп, координатору выжимку и последние 40 строк.

## 5. Установка в Prism и проверка
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/pack-install-SHA7" && cd "${W:?}" && \
P="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher" && OLD="$P/instances/All of Create Aeronautics" && NEW="$P/instances/Airstrike Pack" && \
test ! -e "$NEW" && cp -a "$W/stage/Airstrike Pack" "$NEW" && echo "инстанс скопирован" && \
sha256sum "$NEW/minecraft/mods/airstrike-2.5.0.jar" && ls "$NEW/minecraft/mods" | grep -c '\.jar$' && head -4 "$NEW/instance.cfg" && \
diff <(sha256sum "$OLD/instance.cfg" "$OLD/mmc-pack.json" "$OLD/minecraft/options.txt"; ls -l "$OLD/minecraft/mods" | sha256sum) "$W/old.before" \
  && echo "старый инстанс не изменился" || echo "ПРОВАЛ: старый инстанс изменился"
now=$(findmnt -n -o ID "/run/user/$(id -u)/doc"); [ -n "$now" ] && [ "$now" = "$(cat "$W/doc-mount.before")" ] && echo "маунт doc цел: $now" || echo "ПРОВАЛ: маунт doc пропал или сменился"
busctl --user call org.kde.kglobalaccel /component/kwin org.kde.kglobalaccel.Component isActive
pgrep -a -f -i prismlauncher | cut -c1-120 | head -2 || echo "Prism не запущен — инстанс появится при запуске"
```
Должно быть «инстанс скопирован», sha256 `ce6d503b…063d3`, «старый инстанс не изменился», «маунт doc цел»,
`b true`. Если Prism запущен и нового инстанса в списке нет — он появится после перезапуска Prism (сказать
координатору, Prism не перезапускать).

## Что прислать координатору
Выводы шагов 1–5 (шаг 3 — с последними строками, шаг 4 — выжимку и код). Каталог `$W` остаётся: `stage/` — копия
инстанса до установки, `check/` — запуск проверки.
