# Ноутбук: инстанс Артёма «Airstrike Pack» — на автообновление сборки, проверка запуском до меню

Тред «Автообновление сборки» (PR #207). Запускать **после** выпуска и PR сборки с его jar: кандидат — коммит `main`,
где `pack/` уже ссылается на этот jar. Координатор вписывает его полный SHA вместо `<SHA>`, первые 7 знаков вместо
`SHA7` и версию выпуска вместо `<VER>` во **все** блоки. Артём в это время не играет, Prism закрыт.

**Что делаем.** Инстанс Артёма начинает ставить сборку сам перед каждым запуском (packwiz-installer, команда перед
запуском из `tools/prism_instance.py`), как у друзей. Перед этим в `airstrike-backup/<время>-autoupdate/` уходят
`mods/` целиком (packwiz-installer удаляет только то, что ставил сам: старые jar остались бы рядом с новыми),
`instance.cfg`, `mmc-pack.json` и файлы `config/` сборки. Новая `mods/` — пустая: e4all (его нет в сборке) Артём
убрал 03.10.2026, он остаётся только в резервной копии. Первая установка — здесь же без окна (`-g`): так все необязательные моды (запись: Flashback и его три
мода) встают включёнными, а заодно проверяется, что bootstrap сам берёт packwiz-installer с GitHub. Потом — копия
модов и настроек инстанса (без миров) до меню через `prod_client.py` во вложенном KWin.

**Успех:** установка — выход 0 и `Finished successfully!`; в `mods/` все jar сборки, четыре мода записи включены,
`airstrike-<VER>.jar` с хешем из `pack/`, других jar нет; в `instance.cfg` команда перед запуском; запуск копии —
«Game took», без падений; маунт doc и сочетания KWin целы. При следующем запуске Артёма окно packwiz-installer
скажет «already up to date» и через 10 с само пустит в игру.

## Правила (для каждого блока)
- Каждый блок — **одним вызовом, как написан**; состояние оболочки между вызовами не сохраняется.
- Папка задачи — ровно `/mnt/data/projects/airstrike/mod/run/claude-work/pack-host-SHA7` (новая); уже есть — стоп.
- Меняются только `instance.cfg`, `mods/`, `config/` сборки и новые файлы packwiz в инстансе «Airstrike Pack».
  Миры, `options.txt`, другие инстансы и сам Prism не трогать. Ничего не удалять: только перенос в `airstrike-backup/`.
- Клиент — только через `tools/nested_kwin.sh` (это делает `prod_client.py`); тяжёлое — только `tools/laptop_job.sh`.
  Остановить — только `systemctl --user stop 'airstrike-job-pack-host-*'`; никаких `pkill`/`kill` по имени, `rm -rf`.
- Любой «стоп» или ошибка — координатору сразу (вывод блока), дальше не идти. Откат шага 3 — вернуть из
  `airstrike-backup/<время>-autoupdate/` `instance.cfg` и `mods/` (папку `mods/` после установки — тоже в сторону).

## 1. Проверка перед запуском
```sh
SHA=<SHA>; VER=<VER>; [ ${#SHA} = 40 ] || echo "стоп: SHA не вписан"; [ "$VER" != "<VER>" ] || echo "стоп: версия не вписана"
pgrep -x java >/dev/null && echo "стоп: работает java (игра или другая задача)"
{ pgrep -x prismlauncher || flatpak ps --columns=application | grep -i prismlauncher; } >/dev/null && echo "стоп: Prism запущен — он перепишет instance.cfg своими настройками; попросить Артёма закрыть Prism"
systemctl --user list-units 'airstrike-job-*' --no-legend | grep . && echo "стоп: идёт другая задача"
echo "маунт doc: $(findmnt -n -o ID "/run/user/$(id -u)/doc" || echo "стоп: маунта doc нет")"
test -e /mnt/data/projects/airstrike/mod/run/claude-work/pack-host-SHA7 && echo "стоп: папка pack-host-SHA7 уже есть"
I="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/Airstrike Pack"; MC="$I/minecraft"
test -f "$I/instance.cfg" || echo "стоп: нет инстанса Airstrike Pack"
grep -n 'packwiz' "$I/instance.cfg" && echo "стоп: команда packwiz уже стоит"
grep -E '^(OverrideCommands|PreLaunchCommand|WrapperCommand|PostExitCommand|OverrideJavaLocation|JavaPath)=' "$I/instance.cfg"
grep -E '^(PreLaunchCommand|WrapperCommand|PostExitCommand)=' "$I/../../prismlauncher.cfg" || echo "общих команд Prism нет"
ls "$MC/mods" | grep -E '^e4(all|mc)-' || echo "e4all/e4mc в mods нет"
cd /mnt/data/projects/airstrike && git fetch -q origin main && git merge-base --is-ancestor "$SHA" origin/main && echo "SHA в main" || echo "стоп: SHA не в main"
git show "$SHA:pack/mods/airstrike.pw.toml" | grep -x "filename = \"airstrike-$VER.jar\"" || echo "стоп: в pack/ на SHA не airstrike-$VER.jar"
curl -fsS https://raw.githubusercontent.com/zentixua/airstrike/main/pack/pack.toml | cmp - <(git show "$SHA:pack/pack.toml") && echo "pack.toml на GitHub — как на SHA" || echo "стоп: pack.toml на main другой (или кэш GitHub ещё старый — повторить через 5 мин)"
df -h "$MC" /mnt/data/projects/airstrike/mod/run
```
Свободно — не меньше 2 ГБ в обоих местах. Если `OverrideCommands` не `true`, а у Prism есть общие `WrapperCommand`
или `PostExitCommand`, — стоп, координатору (шаг 3 включает свои команды экземпляра, общие тогда перестанут действовать).

## 2. Worktree кандидата
```sh
W=/mnt/data/projects/airstrike/mod/run/claude-work/pack-host-SHA7 && cd /mnt/data/projects/airstrike && \
git worktree add "$W" <SHA> && cd "$W" && [ "$(git rev-parse HEAD)" = <SHA> ] && echo "коммит верный" && \
findmnt -n -o ID "/run/user/$(id -u)/doc" > "$W/doc-mount.before" && python3 tools/prism_instance.py "$W/inst.zip" && \
unzip -q "$W/inst.zip" -d "$W/inst" && grep '^PreLaunchCommand=' "$W/inst/instance.cfg"
```

## 3. Резервная копия и перевод инстанса
Весь блок — под `set -e`: любая неудача останавливает его до переноса `mods/`.
```sh
bash -euo pipefail <<'SH'
W=/mnt/data/projects/airstrike/mod/run/claude-work/pack-host-SHA7; cd "$W"
I="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/Airstrike Pack"; MC="$I/minecraft"
if pgrep -x java || pgrep -x prismlauncher || flatpak ps --columns=application | grep -i prismlauncher; then
  echo "стоп: игра или Prism запущены"; exit 1
fi
B="$MC/airstrike-backup/$(date +%Y-%m-%d_%H%M%S)-autoupdate"; test ! -e "$B"; mkdir -p "$B"
cp -p "$I/instance.cfg" "$I/mmc-pack.json" "$B/"
for f in $(git show HEAD:pack/index.toml | sed -n 's|^file = "\(config/[^"]*\)"$|\1|p'); do
  if test -e "$MC/$f"; then (cd "$MC" && cp -p --parents "$f" "$B/"); fi
done
mv "$MC/mods" "$B/mods"; mkdir "$MC/mods"
cp "$W/inst/minecraft/packwiz-installer-bootstrap.jar" "$MC/"
python3 - "$I/instance.cfg" "$W/inst/instance.cfg" <<'EOF'
import sys
cfg, ref = sys.argv[1:]
want = {k: v for k, v in (l.split("=", 1) for l in open(ref, encoding="utf-8").read().splitlines() if "=" in l)
        if k in ("OverrideCommands", "PreLaunchCommand")}
lines = open(cfg, encoding="utf-8").read().splitlines()
keys = [l.split("=", 1)[0] if "=" in l and not l.startswith("[") else None for l in lines]
for k, v in want.items():
    if k in keys:
        lines[keys.index(k)] = f"{k}={v}"
    else:
        at = lines.index("[General]") + 1 if "[General]" in lines else 0
        lines.insert(at, f"{k}={v}")
open(cfg, "w", encoding="utf-8").write("\n".join(lines) + "\n")
print("\n".join(l for l in lines if l.split("=", 1)[0] in want))
EOF
echo "резервная копия: $B"; find "$B" -maxdepth 2 -not -path "$B/mods/*"; ls "$MC/mods"
SH
```
Должно кончиться `OverrideCommands=true`, `PreLaunchCommand=\"$INST_JAVA\" -jar packwiz-installer-bootstrap.jar https://…/pack.toml`,
списком резервной копии и пустой `mods/`. Jar старой `mods/`, которых нет в сборке, кроме `airstrike-*` и `e4all-*`,
— назвать координатору (они остались в резервной копии).

## 4. Первая установка без окна (Java инстанса, как `$INST_JAVA`)
```sh
W=/mnt/data/projects/airstrike/mod/run/claude-work/pack-host-SHA7 && cd "${W:?}" && \
P="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher" && I="$P/instances/Airstrike Pack" && MC="$I/minecraft" && \
J="$(sed -n 's/^JavaPath=//p' "$I/instance.cfg")"; [ -x "$J" ] || J="$P/java/java-runtime-delta/bin/java"; echo "Java: $J" && \
URL="$(sed -n 's|^PreLaunchCommand=.* \(https://[^ ]*\)$|\1|p' "$I/instance.cfg")" && echo "сборка: $URL" && \
cp -p "$I/mmc-pack.json" "$W/mmc-pack.before" && \
(cd "$MC" && timeout 900 "$J" -jar packwiz-installer-bootstrap.jar -g "$URL") > "$W/install.log" 2>&1; echo "выход: $?"; \
grep -E "Finished|already up to date|Failed|Exception" "$W/install.log" | tail -5; \
cmp "$I/mmc-pack.json" "$W/mmc-pack.before" && echo "mmc-pack.json не изменился"; \
python3 - "$MC" <<'EOF'
import hashlib, json, os, subprocess, sys, tomllib
mc = sys.argv[1]
idx = tomllib.loads(subprocess.check_output(["git", "show", "HEAD:pack/index.toml"], text=True))
want, opt = {}, []
for e in idx["files"]:
    if e.get("metafile"):
        m = tomllib.loads(subprocess.check_output(["git", "show", "HEAD:pack/" + e["file"]], text=True))
        if m.get("side", "both") != "server" and e["file"].startswith("mods/"):
            want[m["filename"]] = m["download"]
            if m.get("option", {}).get("optional"):
                opt.append(m["filename"])
have = set(os.listdir(os.path.join(mc, "mods")))
print("нет в mods:", sorted(set(want) - have) or "—")
print("лишние в mods:", sorted(have - set(want) - {".index"}) or "—")
print("запись включена:", all(f in have for f in opt), opt)
air = next(f for f in want if f.startswith("airstrike-"))
d, jar = want[air], os.path.join(mc, "mods", air)
h = hashlib.new(d["hash-format"], open(jar, "rb").read()).hexdigest() if os.path.isfile(jar) else None
print(air, "хеш как в сборке" if h == d["hash"] else "ПРОВАЛ: нет jar или хеш не тот")
pw = json.load(open(os.path.join(mc, "packwiz.json")))
print("packwiz.json: файлов", len(pw["cachedFiles"]), "; необязательные включены:",
      all(v.get("optionValue", True) for v in pw["cachedFiles"].values() if v.get("isOptional")))
EOF
```
Годится: выход 0, `Finished successfully!`, «нет в mods: —», «лишние в mods: —», «запись включена:
True», хеш как в сборке. Повторный запуск того же вызова должен сказать `already up to date` (проверить один раз).

## 5. Копия до меню (в фоне, тайм-аут вызова 15 мин; закрывается сама через 300 с, код 143)
```sh
W=/mnt/data/projects/airstrike/mod/run/claude-work/pack-host-SHA7 && cd "${W:?}" && \
export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
I="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/Airstrike Pack" && MC="$I/minecraft" && \
C="$W/check" && test ! -e "$C" && mkdir "$C" && cp -a "$MC/mods" "$MC/config" "$MC/options.txt" "$C/" && \
{ test ! -d "$MC/shaderpacks" || cp -a "$MC/shaderpacks" "$C/"; } && \
sed -i -e 's/^onboardAccessibility:.*/onboardAccessibility:false/' -e 's/^pauseOnLostFocus:.*/pauseOnLostFocus:false/' "$C/options.txt" && \
mapfile -t JV < <(python3 - "$I/instance.cfg" <<'EOF'
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
) && echo "аргументы Java: ${JV[*]}" && \
timeout -k 60 12m tools/laptop_job.sh pack-host-check -- python3 tools/prod_client.py --no-copy --dir "$C" \
  --airstrike-jar "$(ls "$C"/mods/airstrike-*.jar)" --seconds 300 "${JV[@]}"; echo "код $? (143 — закрыт по сроку)"
```
Выжимка (одним вызовом):
```sh
W=/mnt/data/projects/airstrike/mod/run/claude-work/pack-host-SHA7 && cd "${W:?}/check" && python3 - <<'EOF'
import collections, os, re
log = open("logs/latest.log", errors="replace").read().splitlines()
print("запуск:", next((l.split("]: ")[-1] for l in log if "Game took" in l), "НЕТ строки «Game took» — меню не открылось"))
for name in ("mcwifipnp", "Flashback", "Distant", "airstrike"):
    print(f"строк с «{name}»:", sum(name.lower() in l.lower() for l in log))
errs = collections.Counter(re.sub(r"^\[[^]]*\] \[[^]]*/ERROR\] \[([^]/]*).*", r"\1", l) for l in log if "/ERROR]" in l)
print(f"ERROR: {sum(errs.values())} — " + ", ".join(f"{k} {v}" for k, v in errs.most_common(12)))
bad = [l[:300] for l in log if re.search(r"has crashed|Unreported exception|ua\.zentix.*Exception|Missing or unsupported mandatory", l)]
print("падения:", bad[:5] or "нет")
print("crash-reports:", os.listdir("crash-reports") if os.path.isdir("crash-reports") else "нет")
EOF
tail -n 5 logs/latest.log | cut -c1-250
```
Годится: «Game took» есть, падений и `crash-reports` нет. Иначе — стоп, координатору выжимку и последние 40 строк.

## 6. Итог
```sh
W=/mnt/data/projects/airstrike/mod/run/claude-work/pack-host-SHA7 && cd "${W:?}" && \
I="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/Airstrike Pack" && \
grep -E '^(OverrideCommands|PreLaunchCommand)=' "$I/instance.cfg"; ls "$I/minecraft" | grep -E 'packwiz'
now=$(findmnt -n -o ID "/run/user/$(id -u)/doc"); [ "$now" = "$(cat doc-mount.before)" ] && echo "маунт doc цел" || echo "ПРОВАЛ: маунт doc"
busctl --user call org.kde.kglobalaccel /component/kwin org.kde.kglobalaccel.Component isActive
```
Координатору — выводы шагов 1–6. Каталог `$W` (worktree, `check/`, логи) остаётся; уборку решает Артём.
