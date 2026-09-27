# Прицел не цепляет служебные невидимые сущности (клей Create/Simulated, сиденья, облака эффектов и т.п.)
import os, sys, json, re
DP = sys.argv[1]; FN = DP + "/data/shahed/function"
os.makedirs(DP + "/data/shahed/tags/entity_type", exist_ok=True)
def opt(i): return {"id": i, "required": False}
json.dump({"values": ["minecraft:marker", "minecraft:item", "minecraft:experience_orb", "minecraft:block_display", "minecraft:item_display",
    "minecraft:text_display", "minecraft:arrow", "minecraft:spectral_arrow", "minecraft:falling_block", "minecraft:area_effect_cloud",
    "minecraft:leash_knot", "minecraft:lightning_bolt", "minecraft:interaction", "minecraft:fireball", "minecraft:small_fireball",
    "minecraft:wind_charge", "minecraft:snowball", "minecraft:egg", "minecraft:potion", "minecraft:experience_bottle", "minecraft:eye_of_ender",
    opt("simulated:honey_glue"), opt("create:super_glue"), opt("create:seat"), opt("create:crafting_blueprint"), opt("create:potato_projectile")]},
    open(DP + "/data/shahed/tags/entity_type/aim_ignore.json", "w"), indent=1)
OLD = ("type=!minecraft:marker,type=!minecraft:item,type=!minecraft:experience_orb,type=!minecraft:block_display,type=!minecraft:item_display,"
       "type=!minecraft:text_display,type=!minecraft:arrow,type=!minecraft:falling_block")
for n in ("ray/step", "ray/tag_ent"):
    p = f"{FN}/{n}.mcfunction"; s = open(p, encoding="utf-8").read()
    if OLD in s:
        s = s.replace(OLD, "type=!#shahed:aim_ignore"); open(p, "w", encoding="utf-8").write(s)
print("ok")
