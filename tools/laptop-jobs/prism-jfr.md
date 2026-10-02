# Ноутбук: журнал JFR в игре Артёма (строка в аргументы Java сборки в Prism)

Тред «Разбор сегодняшних игр на 2.4.1». Артём разрешил: вопрос координатора 3 «Запись JFR в Prism» в ленте проекта
(06:07 UTC 02.10) и его ответ в 06:24 UTC «#1 Оставляем, остальное согласно твоим советам». Строку он видел в треде
до изменения. Папка задачи — `/mnt/data/projects/airstrike/mod/run/claude-work/prism-jfr` (новая; если она уже есть —
остановиться и сообщить координатору, ничего не удалять и не перезаписывать).

Что меняется у Артёма — только это:
- в `instance.cfg` сборки «All of Create Aeronautics» к значению `JvmArgs` в конец через пробел дописывается строка
  ниже; остальное в файле — байт в байт как было;
- новая папка `PrismLauncher/airstrike-jfr` (и `repo` в ней) — туда игра пишет журнал;
- копия `instance.cfg` до изменения — в `minecraft/airstrike-backup/` сборки.
Больше ничего не трогать: ни моды, ни миры, ни `prismlauncher.cfg`, ни другие сборки. Ничего не удалять.

Строка (её же скрипт ниже держит в `LINE`):
```
-XX:FlightRecorderOptions=repository=/home/artem/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/airstrike-jfr/repo,old-object-queue-size=0 -XX:StartFlightRecording=name=airstrike,settings=default,disk=true,maxage=6h,maxsize=1g,dumponexit=true,filename=/home/artem/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/airstrike-jfr
```
Настройки JFR `default` (нагрузка около 1 %), запись на диск, последние 6 ч и не больше 1 ГБ, при выходе — файл
`hotspot-pid-…jfr` в `airstrike-jfr`. Хранилище записи — на диске, не в `/tmp` (на ноутбуке он в ОЗУ). Образцы старых
объектов выключены (`old-object-queue-size=0`): разбор путей до корней у них ронял JVM 21.0.7 с поколенческим ZGC
(задача leak-chunks, ночь на 02.10).

**Каждый блок ниже — одним вызовом, как написан**: состояние оболочки между вызовами не сохраняется. Блоки с шага 2
начинаются с `W=… && cd "${W:?}"`.

## 1. Проверка (только чтение)
Любой вывод у первых трёх команд, строка «стоп» — не менять ничего, сообщить координатору (Prism или игра открыты —
подождать, пока Артём их закроет).
```sh
pgrep -a -x java | grep -Ei 'neoforge|minecraft'
pgrep -a -x prismlauncher
flatpak ps --columns=application 2>/dev/null | grep -i prismlauncher
P="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"; I="$P/instances/All of Create Aeronautics"
test "$HOME" = /home/artem || echo "HOME не /home/artem — стоп"
test -e "$P/airstrike-jfr" && echo "папка $P/airstrike-jfr уже есть — стоп"
test -e /mnt/data/projects/airstrike/mod/run/claude-work/prism-jfr && echo "папка задачи уже есть — стоп"
test -d "$I/minecraft/airstrike-backup" || echo "нет $I/minecraft/airstrike-backup — стоп"
ls -l "$I/instance.cfg" "$P/prismlauncher.cfg"; head -3 "$I/instance.cfg"
grep -n -i -E '^(\[|[a-z]*java[a-z]*=|jvmargs=|[a-z]*mem[a-z]*=)' "$I/instance.cfg"
grep -n -i -E '^(\[|javapath=|jvmargs=)' "$P/prismlauncher.cfg"
df -h "$P" | tail -1
```
Свободно на диске меньше 5 ГБ — стоп.

## 2. Скрипт и проверка строки на той же Java (у Артёма ничего не меняется)
`check` находит Java сборки (как Prism: `JavaPath` сборки, если `OverrideJavaLocation=true`, иначе общий), запускает
её со строкой (пути заменены на папку задачи) и `-version`, показывает сводку записанного файла и будущую строку
`JvmArgs`. Последняя строка вывода — «можно менять» или «стоп: …».
```sh
W=/mnt/data/projects/airstrike/mod/run/claude-work/prism-jfr && mkdir -p "$W" && cd "${W:?}" && \
cat > "$W/prism_jfr.py" <<'EOF'
# Задача prism-jfr: строка JFR в JvmArgs сборки «All of Create Aeronautics» в Prism (tools/laptop-jobs/prism-jfr.md).
# check — только читает и проверяет строку на Java сборки в папке задачи; apply — копия instance.cfg, папка журнала,
# правка одной строки JvmArgs (остальной файл байт в байт), проверка.
import os, re, shutil, subprocess, sys, time
HOME = os.path.expanduser("~")
P = os.path.join(HOME, ".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher")
I = os.path.join(P, "instances", "All of Create Aeronautics")
CFG, GLOBAL = os.path.join(I, "instance.cfg"), os.path.join(P, "prismlauncher.cfg")
JFR = os.path.join(P, "airstrike-jfr")
def line(jfr):
    return (f"-XX:FlightRecorderOptions=repository={jfr}/repo,old-object-queue-size=0 "
            f"-XX:StartFlightRecording=name=airstrike,settings=default,disk=true,maxage=6h,maxsize=1g,"
            f"dumponexit=true,filename={jfr}")
LINE = line(JFR)
W = os.path.dirname(os.path.abspath(__file__))
def stop(msg):
    print("стоп:", msg)
    sys.exit(1)
def read(path):
    raw = open(path, "rb").read()
    return raw, raw.decode("utf-8").splitlines(keepends=True)
def keys(lines):
    """Ключи раздела [General] (или файла без разделов): имя → (номер строки, сырое значение)."""
    out, section = {}, "General"
    for i, ln in enumerate(lines):
        s = ln.strip()
        if s.startswith("[") and s.endswith("]"):
            section = s[1:-1]
        elif "=" in s and section == "General" and not s.startswith((";", "#")):
            k, v = s.split("=", 1)
            out[k.strip()] = (i, v.strip())
    return out
def plain(raw):
    """Значение без кавычек — только чтобы сравнить; кавычки и \\-экранирование, как у QSettings."""
    if len(raw) >= 2 and raw[0] == raw[-1] == '"':
        return re.sub(r'\\(.)', r'\1', raw[1:-1])
    return raw
def new_jvmargs(raw, qsettings):
    if "StartFlightRecording" in raw or "FlightRecorderOptions" in raw:
        stop("в JvmArgs уже есть JFR — не дублирую")
    if not qsettings:
        # старый формат MultiMC (без [General]): кавычки — часть значения, экранируются только \\, # и переводы строк
        return (raw + " " if raw else "") + LINE
    if len(raw) >= 2 and raw[0] == raw[-1] == '"':
        inner = raw[1:-1]
        if re.search(r'(?<!\\)"', inner.replace("\\\\", "")):
            stop("в кавычках JvmArgs есть кавычка без \\ — не понимаю формат")
        return '"' + inner + " " + LINE + '"'
    if any(c in raw for c in '"\\;,#'):
        stop("JvmArgs без кавычек, но со спецсимволами — не понимаю формат")
    # строка с «,» и «=»: в INI QSettings такое значение только в кавычках, иначе это список
    return '"' + (raw + " " if raw else "") + LINE + '"'
def java_of(inst, glob):
    path = None
    if inst.get("OverrideJavaLocation", (0, "false"))[1].lower() == "true":
        path = plain(inst.get("JavaPath", (0, ""))[1])
    if not path:
        path = plain(glob.get("JavaPath", (0, ""))[1])
    if path and not os.path.isabs(path):
        path = os.path.join(P, path)
    fallback = os.path.join(P, "java/java-runtime-delta/bin/java")
    if not path or not os.path.exists(path):
        print(f"Java сборки «{path}» не видна с хоста (в flatpak) — проверяю на {fallback}")
        path = fallback
    return path
def guard(inst):
    if HOME != "/home/artem":
        stop(f"HOME {HOME}, а в строке /home/artem")
    if os.path.exists(JFR):
        stop(f"{JFR} уже есть")
    if inst.get("OverrideJavaArgs", (0, ""))[1].lower() != "true" or "JvmArgs" not in inst:
        stop("у сборки нет своих аргументов Java (OverrideJavaArgs=true и JvmArgs) — сообщить координатору")
def check():
    raw, lines = read(CFG)
    inst, glob = keys(lines), keys(read(GLOBAL)[1])
    print("instance.cfg:", len(raw), "байт, строк", len(lines), "| первая строка:", lines[0].strip() if lines else "")
    guard(inst)
    i, old = inst["JvmArgs"]
    qs = any(ln.strip() == "[General]" for ln in lines)
    print("формат:", "QSettings ([General])" if qs else "старый MultiMC (без разделов)")
    new = new_jvmargs(old, qs)
    java = java_of(inst, glob)
    t = os.path.join(W, "test")
    os.makedirs(os.path.join(t, "repo"), exist_ok=True)
    test = line(t).split(" ")
    r = subprocess.run([java, *test, "-version"], capture_output=True, text=True, timeout=120)
    print(f"проверка: {java} {' '.join(test)} -version → код {r.returncode}")
    print((r.stdout + r.stderr).strip()[-2000:])
    files = [f for f in os.listdir(t) if f.endswith(".jfr")]
    print("записано:", files)
    if r.returncode != 0 or not files:
        stop("Java не приняла строку или не записала файл")
    jfr = os.path.join(os.path.dirname(java), "jfr")
    if os.path.exists(jfr):
        s = subprocess.run([jfr, "summary", os.path.join(t, files[0])], capture_output=True, text=True, timeout=120)
        out = s.stdout.splitlines()
        print("\n".join(out[:12]))
        print("\n".join(l for l in out if "OldObjectSample" in l or "ExecutionSample" in l or "GCConfiguration" in l))
    print(f"JvmArgs (строка {i + 1}) сейчас:\n{old}\nбудет:\n{new}")
    print("можно менять")
def apply():
    for p in subprocess.run(["pgrep", "-x", "prismlauncher"], capture_output=True, text=True).stdout.split():
        stop(f"Prism открыт (pid {p})")
    raw, lines = read(CFG)
    inst = keys(lines)
    guard(inst)
    i, old = inst["JvmArgs"]
    qs = any(ln.strip() == "[General]" for ln in lines)
    new = new_jvmargs(old, qs)
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    backup = os.path.join(I, "minecraft", "airstrike-backup", f"instance.cfg.{stamp}.before-jfr")
    if os.path.exists(backup):
        stop(f"{backup} уже есть")
    shutil.copy2(CFG, backup)
    if open(backup, "rb").read() != raw:
        stop("копия не совпала с файлом")
    print("копия:", backup)
    os.makedirs(os.path.join(JFR, "repo"))
    ln = lines[i]
    end = ln[len(ln.rstrip("\r\n")):]
    k = ln.split("=", 1)[0]
    lines[i] = k + "=" + new + end
    tmp = CFG + ".airstrike-tmp"
    with open(tmp, "wb") as f:
        f.write("".join(lines).encode("utf-8"))
    os.replace(tmp, CFG)
    raw2, lines2 = read(CFG)
    got = keys(lines2)["JvmArgs"][1]
    other = [a for a, b in zip(raw.decode().splitlines(), raw2.decode().splitlines()) if a != b]
    base = plain(old) if qs else old
    want = (base + " " if base else "") + LINE
    ok = got == new and len(lines2) == len(lines) and len(other) == 1 and (plain(got) if qs else got) == want
    print(f"JvmArgs теперь:\n{got}\nизменённых строк файла: {len(other)}")
    print("готово" if ok else "стоп: файл после записи не тот — вернуть копию: cp -p '" + backup + "' '" + CFG + "'")
    sys.exit(0 if ok else 1)
{"check": check, "apply": apply}[sys.argv[1]]()
EOF
python3 -m py_compile "$W/prism_jfr.py" && python3 "$W/prism_jfr.py" check
```

## 3. Изменение (только если шаг 2 кончился «можно менять», Prism и игра закрыты)
```sh
W=/mnt/data/projects/airstrike/mod/run/claude-work/prism-jfr && cd "${W:?}" && \
pgrep -a -x prismlauncher; pgrep -a -x java | grep -Ei 'neoforge|minecraft'; \
python3 "$W/prism_jfr.py" apply; echo "код $?"; ls -la "$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/airstrike-jfr"
```
Последняя строка скрипта — «готово». «стоп» — сообщить координатору дословно (до записи файл не меняется; после
записи скрипт сам пишет команду, которой вернуть копию, — её не выполнять без координатора).

## 4. Отчёт координатору (≤ 40 КБ)
Вывод шагов 1–3 целиком. Как вернуть (если Артём попросит, при закрытом Prism): скопировать файл из
`minecraft/airstrike-backup/instance.cfg.<время>.before-jfr` обратно в `instance.cfg` сборки; папку `airstrike-jfr`
не удалять без слова Артёма.
