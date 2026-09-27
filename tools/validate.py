#!/usr/bin/env python3
"""Статическая проверка датапака shahed (Minecraft 1.21.1).

Запуск из любого места:  python3 tools/validate.py
Пути к игре — в tools/paths.py (переопределяются MC_DIR / MC_JAR / MC_ASSETS).

Что проверяет:
  * вызовы функций: существование, макро-функции вызываются с `with` или `{...}`, и наоборот
  * `$`-строки содержат $(…), и $(…) не встречается вне макро-строк
  * константы #N и objectives, используемые в коде, объявлены в load
  * баланс скобок {} [] вне строк
  * JSON в tellraw/title и во всех .json файлах
  * звуки из playsound существуют (моды + пакет Shahed Sounds + ваниль, если найдены assets)
  * частицы существуют (ваниль из jar + моды)
Код выхода 1, если найдены ошибки.
"""
import glob, json, os, re, sys, zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from paths import ROOT, DATAPACK as DP, RESOURCEPACK as RP, MC, JAR, ASSETS, MODS

FN = os.path.join(DP, "data/shahed/function")
funcs = {}
for p in glob.glob(FN + "/**/*.mcfunction", recursive=True):
    funcs["shahed:" + os.path.relpath(p, FN)[:-11].replace(os.sep, "/")] = open(p, encoding="utf-8").read().splitlines()
macro = {n for n, l in funcs.items() if any(x.startswith("$") for x in l)}
load = "\n".join(funcs.get("shahed:load", []))
consts = set(re.findall(r"scoreboard players set (#[-\w]+) shahed ", load))
objs = set(re.findall(r"scoreboard objectives add (\w+)", load))

errs = []
for n, lines in funcs.items():
    for i, l in enumerate(lines, 1):
        if l.startswith("#") or not l.strip():
            continue
        if l.startswith("$") and "$(" not in l:
            errs.append(f"{n}:{i} макро-строка без $(…)")
        if not l.startswith("$") and "$(" in l:
            errs.append(f"{n}:{i} $(…) вне макро-строки")
        if "tellraw" not in l and "title " not in l:
            for m in re.finditer(r'(?<![\w:"])function (shahed:[\w/]+)( with| \{)?', l):
                t = m.group(1)
                if t not in funcs:
                    errs.append(f"{n}:{i} нет функции {t}")
                elif (t in macro) != bool(m.group(2)):
                    errs.append(f"{n}:{i} {'макро без аргументов' if t in macro else 'аргументы у не-макро'}: {t}")
        for c in re.findall(r"(#-?\d+) shahed\b", l):
            if c not in consts and not re.search(r"players (add|remove|set) " + re.escape(c) + " ", l):
                errs.append(f"{n}:{i} константа {c} не объявлена в load")
        for o in re.findall(r"(?:@s|@a|@e\[[^\]]*\]|#[\w-]+) (shahed_\w+)\b", l):
            if o not in objs:
                errs.append(f"{n}:{i} objective {o} не объявлен в load")
        st, q, esc = [], False, False
        for ch in l:
            if esc:
                esc = False; continue
            if ch == "\\":
                esc = True; continue
            if ch == '"':
                q = not q
            if q:
                continue
            if ch in "{[":
                st.append(ch)
            elif ch in "}]":
                if not st or {"}": "{", "]": "["}[ch] != st.pop():
                    errs.append(f"{n}:{i} скобки"); break
        else:
            if st:
                errs.append(f"{n}:{i} незакрытая скобка")
        m = re.search(r"(?:tellraw|title) \S+ (?:actionbar |title |subtitle )?(\[.*\]|\{.*\})\s*$", l)
        if m and "$(" not in l:
            try:
                json.loads(m.group(1))
            except Exception as e:
                errs.append(f"{n}:{i} JSON текста: {e}")

txt = "\n".join(sum(funcs.values(), []))
for v in re.findall(r'set value "(shahed:[\w/]+)"', txt):
    if v not in funcs and not re.match(r"shahed:(drone|missile)\.", v):
        errs.append("нет функции (строка в storage): " + v)

for r, _, fs in os.walk(DP):
    for x in fs:
        if x.endswith((".json", ".mcmeta")):
            try:
                json.load(open(os.path.join(r, x), encoding="utf-8"))
            except Exception as e:
                errs.append(f"JSON {os.path.relpath(os.path.join(r, x), ROOT)}: {e}")

# ---- звуки ----
snd, notes = set(), []
for j in glob.glob(MODS + "/*.jar"):
    try:
        z = zipfile.ZipFile(j)
    except Exception:
        continue
    for f in z.namelist():
        if re.match(r"assets/[^/]+/sounds\.json$", f):
            try:
                snd |= {f.split("/")[1] + ":" + k for k in json.loads(z.read(f))}
            except Exception:
                pass
try:
    snd |= {"shahed:" + k for k in json.load(open(os.path.join(RP, "assets/shahed/sounds.json"), encoding="utf-8"))}
except Exception as e:
    errs.append(f"пакет звуков: {e}")
have_vanilla = False
for idx in sorted(glob.glob(os.path.join(ASSETS, "indexes", "*.json")), key=os.path.getmtime, reverse=True):
    try:
        h = json.load(open(idx))["objects"]["minecraft/sounds.json"]["hash"]
        snd |= {"minecraft:" + k for k in json.load(open(os.path.join(ASSETS, "objects", h[:2], h)))}
        have_vanilla = True
        break
    except Exception:
        continue
if not have_vanilla:
    notes.append("ванильные звуки не проверены (нет assets Prism) — minecraft:* пропущены")
used = set(re.findall(r"playsound ([\w:./]+)", txt)) | set(re.findall(r'snd\.e set value "([\w:./]+)"', txt))
missing_snd = sorted(s for s in used if s not in snd and not (s.startswith("minecraft:") and not have_vanilla))

# ---- частицы ----
parts = {"minecraft:block", "minecraft:block_marker", "minecraft:falling_dust", "minecraft:dust", "minecraft:dust_color_transition",
         "minecraft:item", "minecraft:entity_effect", "minecraft:explosion_emitter", "minecraft:gust_emitter_large",
         "minecraft:gust_emitter_small", "minecraft:vibration", "minecraft:trail", "minecraft:shriek", "minecraft:sculk_charge"}
have_jar = os.path.isfile(JAR)
if have_jar:
    mcz = zipfile.ZipFile(JAR)
    parts |= {"minecraft:" + os.path.basename(f)[:-5] for f in mcz.namelist() if f.startswith("assets/minecraft/particles/")}
else:
    notes.append("клиентский jar не найден — ванильные частицы не проверены")
for j in glob.glob(MODS + "/*.jar"):
    try:
        z = zipfile.ZipFile(j)
    except Exception:
        continue
    parts |= {f.split("/")[1] + ":" + os.path.basename(f)[:-5] for f in z.namelist() if re.match(r"assets/[^/]+/particles/[^/]+\.json$", f)}
missing_part = sorted(p for p in set(re.findall(r"particle ([\w:]+)", txt)) if p not in parts and (have_jar or not p.startswith("minecraft:")))

print(f"функций: {len(funcs)} (макро: {len(macro)})")
for e in errs[:60]:
    print("ОШИБКА", e)
if len(errs) > 60:
    print(f"… и ещё {len(errs) - 60}")
for s in missing_snd:
    print("НЕТ ЗВУКА", s)
for p in missing_part:
    print("НЕТ ЧАСТИЦЫ", p)
for n in notes:
    print("заметка:", n)
bad = len(errs) + len(missing_snd) + len(missing_part)
print("OK" if not bad else f"проблем: {bad}")
sys.exit(1 if bad else 0)
