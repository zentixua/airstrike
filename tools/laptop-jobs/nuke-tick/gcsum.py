# Лог сборщика G1 (-Xlog:gc*) → сборки, после которых старое поколение стало меньше (смешанные, полные): время,
# номер, вид, старое поколение после (МБ; «с огромными» — как пул «G1 Old Gen» у JMX) — сверка со строкой «ДИАГ куча» мода. Аргументы: gc-full.log [latest.log].
import re, sys
region = None
gcs = {}
for line in open(sys.argv[1], errors="replace"):
    m = re.search(r"Heap Region Size: (\d+)M", line)
    if m:
        region = int(m.group(1))
    m = re.search(r"^\[([^\]]+)\].*GC\((\d+)\) (Pause [A-Za-z]+(?: \([A-Za-z ]+\))?)", line)
    if m and "->" not in line:
        gcs.setdefault(int(m.group(2)), {})["kind"] = m.group(3)
        gcs[int(m.group(2))].setdefault("at", m.group(1)[11:23])
    m = re.search(r"GC\((\d+)\) Old regions: (\d+)->(\d+)", line)
    if m:
        g = gcs.setdefault(int(m.group(1)), {})
        g["old"] = (int(m.group(2)), int(m.group(3)))
    m = re.search(r"GC\((\d+)\) Humongous regions: (\d+)->(\d+)", line)
    if m:
        gcs.setdefault(int(m.group(1)), {})["hum"] = (int(m.group(2)), int(m.group(3)))
    m = re.search(r"GC\((\d+)\) Pause .* (\d+)M->(\d+)M\((\d+)M\) ([\d.]+)ms", line)
    if m:
        g = gcs.setdefault(int(m.group(1)), {})
        g["heap"] = (int(m.group(2)), int(m.group(3)), int(m.group(4)))
        g["ms"] = float(m.group(5))
print(f"регион {region} МБ, сборок {len(gcs)}")
freed = [(k, g) for k, g in sorted(gcs.items()) if "old" in g and g["old"][1] < g["old"][0]]
print(f"сборок, после которых старое поколение меньше: {len(freed)}")
for k, g in freed[-30:]:
    o, h = g["old"], g.get("hum", (0, 0))
    r = region or 0
    print(f"  {g.get('at', '?')} GC({k}) {g.get('kind', '?')}: старое {o[0] * r}→{o[1] * r} МБ, с огромными {(o[0] + h[0]) * r}→{(o[1] + h[1]) * r} МБ, куча {g.get('heap')}, {g.get('ms', '?')} мс")
long = sorted(((g.get("ms", 0), k, g) for k, g in gcs.items()), reverse=True)[:5]
print("самые долгие паузы: " + ", ".join(f"GC({k}) {g.get('kind', '?')} {ms} мс в {g.get('at', '?')}" for ms, k, g in long))
if len(sys.argv) > 2:
    diag = [l.strip()[:220] for l in open(sys.argv[2], errors="replace") if "ДИАГ куча" in l]
    print(f"строк «ДИАГ куча»: {len(diag)}")
    for l in diag[:5] + (["…"] if len(diag) > 15 else []) + diag[max(5, len(diag) - 10):]:
        print("  " + l)
