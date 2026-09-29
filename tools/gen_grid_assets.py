#!/usr/bin/env python3
"""Модели и состояния блоков сети (блэкаут) — из ванильных, чтобы лампа без питания выглядела той же лампой.

  python3 tools/gen_grid_assets.py [client.jar]
      → mod/src/main/resources/assets/airstrike/blockstates/unlit_*.json, substation.json,
        models/block/unlit_*.json, substation*.json, models/item/substation.json

Двойник лампы (airstrike:unlit_*) берёт ванильное состояние блока-лампы:
- у ламп с «выключенной» моделью (лампа редстоуна, медные лампы) горящие варианты смотрят на выключенную модель;
- у остальных (светокамень, морской фонарь, грибосвет, жабосветы, фонари, стержень края) — копия ванильной модели
  с tintindex на всех гранях: клиент притушивает её цветом (AirstrikeClient.blockColors);
- невидимый блок света — как есть.
Нужен клиентский jar Minecraft 1.21.1 (по умолчанию — из кэша NeoForm Gradle). Только стандартная библиотека.
"""
import json
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "airstrike")
JAR = os.path.expanduser("~/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar")

# двойник → лампа (как в ModBlocks.UNLIT)
LAMPS = ["glowstone", "sea_lantern", "shroomlight", "ochre_froglight", "verdant_froglight", "pearlescent_froglight",
         "lantern", "soul_lantern", "end_rod", "redstone_lamp"] + [
    f"{w}{o}copper_bulb" for w in ("", "waxed_") for o in ("", "exposed_", "weathered_", "oxidized_")] + ["light"]
TINTED = {"glowstone", "sea_lantern", "shroomlight", "ochre_froglight", "verdant_froglight", "pearlescent_froglight",
          "lantern", "soul_lantern", "end_rod"}


def off_model(model):
    """Выключенная модель лампы: redstone_lamp_on → redstone_lamp, copper_bulb_lit_powered → copper_bulb_powered."""
    return model.replace("_lit", "").replace("redstone_lamp_on", "redstone_lamp")


def write(rel, data):
    path = os.path.join(ASSETS, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2)
        fh.write("\n")


def main():
    jar = zipfile.ZipFile(sys.argv[1] if len(sys.argv) > 1 else JAR)

    def vanilla(kind, name):
        return json.loads(jar.read(f"assets/minecraft/{kind}/{name}.json"))

    def elements(model):
        """Грани модели с учётом родителей: первый в цепочке, у кого они есть."""
        name = model.removeprefix("minecraft:")
        while True:
            m = vanilla("models", name)
            if "elements" in m:
                return m["elements"]
            name = m["parent"].removeprefix("minecraft:")

    for lamp in LAMPS:
        state = vanilla("blockstates", lamp)
        for variant in state["variants"].values():
            for v in variant if isinstance(variant, list) else [variant]:
                model = v["model"]
                if lamp in TINTED:
                    base = model.split("/")[-1]
                    tinted = json.loads(json.dumps(elements(model)))
                    for el in tinted:
                        for face in el["faces"].values():
                            face["tintindex"] = 0
                    write(f"models/block/unlit_{base}.json", {"parent": model, "elements": tinted})
                    v["model"] = f"airstrike:block/unlit_{base}"
                elif lamp != "light":
                    v["model"] = off_model(model)
        write(f"blockstates/unlit_{lamp}.json", state)

    # подстанция: фасад (табличка) — на север, повороты по FACING; выбитая — обгоревшие текстуры
    rot = {"north": 0, "east": 90, "south": 180, "west": 270}
    write("blockstates/substation.json", {"variants": {
        f"facing={f},powered={p}": ({"model": "airstrike:block/substation" + ("" if p == "true" else "_burnt")} | ({"y": y} if y else {}))
        for f, y in rot.items() for p in ("true", "false")}})
    write("models/block/substation.json", substation(""))
    write("models/block/substation_burnt.json", {"parent": "airstrike:block/substation", "textures": {
        k: f"airstrike:block/substation_{k}_burnt" for k in ("side", "front", "top", "fins", "insulator")} | {
        "particle": "airstrike:block/substation_side_burnt"}})
    write("models/item/substation.json", {"parent": "airstrike:block/substation"})
    print("ok")


def box(frm, to, faces, uv=None):
    """Коробка: грани {"north": "#tex", …}; uv — по координатам, как у ванильных моделей без явных uv."""
    return {"from": frm, "to": to, "faces": {d: {"texture": t} | ({"cullface": d} if d == "down" and frm[1] == 0 else {})
                                             for d, t in faces.items()}}


def substation(_):
    """Бетонная плита, бак трансформатора с табличкой спереди, радиаторы по бокам, три ввода с изоляторами сверху."""
    six = ("north", "south", "east", "west", "up", "down")
    els = [
        box([0, 0, 0], [16, 2, 16], {d: "#base" for d in six}),
        box([2, 2, 2], [14, 12, 14], {"north": "#front", "south": "#side", "east": "#side", "west": "#side", "up": "#top", "down": "#top"}),
        box([0.5, 3, 3], [2, 11, 13], {"north": "#fins", "south": "#fins", "west": "#fins", "up": "#top", "down": "#top"}),
        box([14, 3, 3], [15.5, 11, 13], {"north": "#fins", "south": "#fins", "east": "#fins", "up": "#top", "down": "#top"}),
    ]
    for x in (3.5, 7, 10.5):
        els.append(box([x, 12, 7], [x + 2, 15, 9], {d: "#insulator" for d in ("north", "south", "east", "west")}))
        els.append(box([x + 0.5, 15, 7.5], [x + 1.5, 16, 8.5], {d: "#top" for d in six if d != "down"}))
    return {"parent": "minecraft:block/block", "ambientocclusion": False, "textures": {
        "particle": "airstrike:block/substation_side", "base": "airstrike:block/substation_base",
        "side": "airstrike:block/substation_side", "front": "airstrike:block/substation_front",
        "top": "airstrike:block/substation_top", "fins": "airstrike:block/substation_fins",
        "insulator": "airstrike:block/substation_insulator"}, "elements": els}


if __name__ == "__main__":
    main()
