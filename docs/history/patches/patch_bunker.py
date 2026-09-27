# Бетонобойная бомба (GBU-57-подобная) с бомбардировщиком B-2.
import os, sys, json, math
DP = sys.argv[1]; FN = DP + "/data/shahed/function"; TG = DP + "/data/shahed/tags/block"
def rd(n): return open(f"{FN}/{n}.mcfunction", encoding="utf-8").read()
def wr(n, t):
    p = f"{FN}/{n}.mcfunction"; os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w", encoding="utf-8").write(t.strip("\n") + "\n")
def tag(n, vals):
    os.makedirs(TG, exist_ok=True)
    json.dump({"values": vals}, open(f"{TG}/{n}.json", "w"), indent=1)
def opt(i): return {"id": i, "required": False}
def f4(v): return f"{round(v,4):g}f"
def V(v): return "[" + ",".join(f4(x) for x in v) + "]"

# ---------- теги блоков ----------
tag("drillable", ["#minecraft:mineable/pickaxe", "#minecraft:mineable/shovel", "#minecraft:mineable/axe", "#minecraft:mineable/hoe"])
tag("bb_soil", ["#minecraft:dirt", "#minecraft:sand", "minecraft:gravel", "minecraft:clay", "minecraft:snow_block", "minecraft:snow",
                "minecraft:powder_snow", "minecraft:mud", "minecraft:soul_sand", "minecraft:soul_soil", "minecraft:farmland", "minecraft:dirt_path"])
tag("bb_soft", ["#shahed:bb_soil", "#minecraft:leaves", "#minecraft:wool", "minecraft:hay_block", "minecraft:moss_carpet"])
tag("bb_rock", ["#minecraft:base_stone_overworld", "#minecraft:stone_ore_replaceables", "minecraft:cobblestone", "minecraft:mossy_cobblestone",
                "minecraft:calcite", "minecraft:dripstone_block", "minecraft:sandstone", "minecraft:red_sandstone", "minecraft:netherrack",
                "minecraft:basalt", "minecraft:smooth_basalt", "minecraft:blackstone", "minecraft:end_stone", "#minecraft:terracotta",
                "#minecraft:coal_ores", "#minecraft:iron_ores", "#minecraft:copper_ores", "#minecraft:gold_ores", "#minecraft:redstone_ores",
                "#minecraft:lapis_ores", "#minecraft:diamond_ores", "#minecraft:emerald_ores", "minecraft:amethyst_block"])
tag("bb_deep", ["minecraft:deepslate", "minecraft:cobbled_deepslate", "#minecraft:deepslate_ore_replaceables"])
COL = ["white","orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan","purple","blue","brown","green","red","black"]
tag("bb_hard", [f"minecraft:{c}_concrete" for c in COL] + ["minecraft:bricks", "#minecraft:stone_bricks", "minecraft:deepslate_bricks",
                "minecraft:deepslate_tiles", "minecraft:cracked_deepslate_bricks", "minecraft:cracked_deepslate_tiles", "minecraft:polished_deepslate",
                "minecraft:polished_blackstone_bricks", "minecraft:prismarine", "minecraft:prismarine_bricks", "minecraft:dark_prismarine",
                "minecraft:end_stone_bricks", "minecraft:nether_bricks", "minecraft:mud_bricks", "#minecraft:beacon_base_blocks",
                "minecraft:smooth_stone", "minecraft:quartz_block", "minecraft:purpur_block", opt("create:industrial_iron_block")])
tag("bb_vhard", ["minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:respawn_anchor", "minecraft:ancient_debris", "minecraft:netherite_block"])
tag("bb_stop", ["minecraft:bedrock", "minecraft:reinforced_deepslate", "minecraft:barrier", "minecraft:end_portal_frame", "minecraft:command_block",
                "minecraft:chain_command_block", "minecraft:repeating_command_block", "minecraft:structure_block", "minecraft:jigsaw"])
tag("bb_fluid", ["minecraft:water", "minecraft:lava", "minecraft:bubble_column"])
os.makedirs(DP + "/data/shahed/predicate", exist_ok=True)
json.dump({"condition": "minecraft:location_check", "predicate": {"can_see_sky": True}}, open(DP + "/data/shahed/predicate/sky.json", "w"), indent=1)

# ---------- load / tick ----------
t = rd("load")
if "shahed_quake" not in t:
    t = t.replace("scoreboard objectives add shahed dummy\n", "scoreboard objectives add shahed dummy\n" + "".join(
        f"scoreboard objectives add {o} dummy\n" for o in ["shahed_E", "shahed_trav", "shahed_fuse", "shahed_quake", "shahed_rel"]))
    t += """scoreboard players set #300 shahed 300
execute unless data storage shahed:cfg bunker_power run data modify storage shahed:cfg bunker_power set value 20
execute unless data storage shahed:cfg bunker_energy run data modify storage shahed:cfg bunker_energy set value 1000
execute unless data storage shahed:cfg collapse run data modify storage shahed:cfg collapse set value 1b
# новая версия пакета звуков: у кого стоит старая — попросить обновить
execute as @a[scores={shahed_mode=1}] run function shahed:rp/prompt
"""
    wr("load", t)
t = rd("tick")
if "bunker/tick" not in t:
    t = t.replace("tag=shahed_root,tag=!shahed_missile] at @s run function shahed:drone/tick",
                  "tag=shahed_root,tag=!shahed_missile,tag=!shahed_bunker,tag=!shahed_bomber] at @s run function shahed:drone/tick")
    t += """
execute as @e[type=minecraft:marker,tag=shahed_root,tag=shahed_bomber] at @s run function shahed:bunker/bomber_tick
execute as @e[type=minecraft:marker,tag=shahed_root,tag=shahed_bunker] at @s run function shahed:bunker/tick
execute as @e[type=minecraft:marker,tag=shahed_bfx] at @s run function shahed:bfx/tick
execute as @e[type=minecraft:marker,tag=shahed_vent_on] at @s run function shahed:bfx/vent_tick
execute as @e[type=minecraft:marker,tag=shahed_surf] at @s run function shahed:bfx/surf_tick
execute as @a[scores={shahed_quake=1..}] at @s run function shahed:bfx/quake
"""
    wr("tick", t)

# ---------- версии пакета звуков: 1 — шахед/ракета, 2 — + бомба ----------
for n in ["snd/listener", "drone/siren", "missile/siren", "fx/arrive", "mfx/arrive", "rp/rejoin"]:
    s = rd(n).replace("shahed_mode=1}", "shahed_mode=1..}")
    if n == "rp/rejoin": s = s.replace("unless score @s shahed_mode matches 1 run", "unless score @s shahed_mode matches 2.. run")
    wr(n, s)
s = rd("snd/listener")
if "#cm" not in s:
    s = s.replace("execute if score @s shahed_mode matches 1 run function shahed:snd/play_custom\nexecute unless score @s shahed_mode matches 1 run function shahed:snd/play_fallback\n",
"""scoreboard players set #cm shahed 0
execute if score @s shahed_mode matches 1.. if score #kind shahed matches ..2 run scoreboard players set #cm shahed 1
execute if score @s shahed_mode matches 2.. run scoreboard players set #cm shahed 1
execute if score #cm shahed matches 1 run function shahed:snd/play_custom
execute if score #cm shahed matches 0 run function shahed:snd/play_fallback
""")
    s = s.replace("execute if score #kind shahed matches 2 run scoreboard players set #C shahed 240\n",
                  "execute if score #kind shahed matches 2 run scoreboard players set #C shahed 240\nexecute if score #kind shahed matches 3.. run scoreboard players set #C shahed 515\n")
    s = s.replace("execute if score #kind shahed matches 2 run scoreboard players set #g shahed 45000\n",
                  "execute if score #kind shahed matches 2 run scoreboard players set #g shahed 45000\nexecute if score #kind shahed matches 3 run scoreboard players set #g shahed 120000\nexecute if score #kind shahed matches 4 run scoreboard players set #g shahed 40000\n")
    assert "#cm" in s and "120000" in s
    wr("snd/listener", s)
s = rd("snd/source")
if "shahed_bomber" not in s:
    s = s.replace("execute if entity @s[tag=shahed_missile] run scoreboard players set #kind shahed 2\n",
        "execute if entity @s[tag=shahed_missile] run scoreboard players set #kind shahed 2\nexecute if entity @s[tag=shahed_bomber] run scoreboard players set #kind shahed 3\nexecute if entity @s[tag=shahed_bunker] run scoreboard players set #kind shahed 4\n")
    wr("snd/source", s)
s = rd("snd/play_custom")
if "pick_jet" not in s:
    wr("snd/play_custom", s.replace("execute if score #kind shahed matches 2 run function shahed:snd/pick_missile\n",
        "execute if score #kind shahed matches 2 run function shahed:snd/pick_missile\nexecute if score #kind shahed matches 3 run function shahed:snd/pick_jet\nexecute if score #kind shahed matches 4 run function shahed:snd/pick_fall\n"))
s = rd("snd/play_fallback")
if "fb_jet" not in s:
    wr("snd/play_fallback", s + "\nexecute if score #kind shahed matches 3 run function shahed:snd/fb_jet\nexecute if score #kind shahed matches 4 run function shahed:snd/fb_fall")
wr("snd/pick_jet", """
execute if score #var shahed matches ..1 run data modify storage shahed:tmp snd.e set value "shahed:bb.jet.mid"
execute if score #var shahed matches 2 run data modify storage shahed:tmp snd.e set value "shahed:bb.jet.far"
""")
wr("snd/pick_fall", "\n".join(f'execute if score #var shahed matches {i} run data modify storage shahed:tmp snd.e set value "shahed:bb.fall.{d}"' for i, d in enumerate(["near", "mid", "far"])))
fbtpl = rd("snd/fb_missile")   # образец: турбина
wr("snd/fb_jet", fbtpl.replace("petrochem:turbine", "minecraft:item.elytra.flying").replace("set #k shahed 175", "set #k shahed 55"))
wr("snd/fb_fall", fbtpl.replace("petrochem:turbine", "minecraft:item.elytra.flying").replace("set #k shahed 175", "set #k shahed 150"))

wr("rp/on", """
scoreboard players set @s shahed_mode 2
tellraw @s {"text":"✔ Полные звуки включены (версия 2: шахед, ракета, бомба). Проверить снова: /trigger shahed_rp set 2","color":"green"}
""")
wr("rp/test", """
scoreboard players set @s shahed_test 40
tellraw @s {"text":"Слушай: гул бомбардировщика, затем свист падающей бомбы…","color":"aqua"}
""")
s = rd("rp/test_tick").replace("playsound shahed:drone.near ambient @s ^2 ^1 ^3 1 1 0", "playsound shahed:bb.jet.mid ambient @s ^2 ^1 ^3 1 1 0").replace(
    "playsound shahed:missile.front.near ambient @s ^-2 ^1 ^3 1 1.3 0", "playsound shahed:bb.fall.near ambient @s ^-2 ^1 ^3 1 1.2 0")
wr("rp/test_tick", s)
s = rd("rp/prompt").replace("полные звуки (мопед шахеда, свист ракеты) — из пакета ресурсов ", "полные звуки — из пакета ресурсов ").replace(
    '{"text":"Shahed Sounds","color":"yellow"}', '{"text":"Shahed Sounds v2","color":"yellow"}').replace("Возьми Shahed_Sounds.zip", "Возьми Shahed_Sounds_v2.zip")
wr("rp/prompt", s)
wr("sound_all", """
# хост: у всех онлайн стоит Shahed Sounds v2 — включить полные звуки всем сразу
execute as @a run scoreboard players set @s shahed_mode 2
tellraw @a {"text":"🔊 Полные звуки Shahed Sounds v2 включены для всех игроков.","color":"green"}
""")

# ---------- модели ----------
TAGS = 'Tags:["shahed","shahed_part","shahed_newpart"],teleport_duration:1,brightness:{sky:15,block:11}'
def box(block, lo, hi, props=None, rotz90=False, vr=6):
    p = "" if not props else ",Properties:{" + ",".join(f'{k}:"{v}"' for k, v in props.items()) + "}"
    w, h, d = hi[0]-lo[0], hi[1]-lo[1], hi[2]-lo[2]
    if not rotz90:
        tr, sc, lr = lo, [w, h, d], "[0f,0f,0f,1f]"
    else:   # поворот на 90° вокруг оси бомбы: блок X → мир Y, блок Y → мир −X
        tr, sc, lr = [lo[0]+w, lo[1], lo[2]], [h, w, d], "[0f,0f,0.7071f,0.7071f]"
    return (f'summon minecraft:block_display ~ ~ ~ {{{TAGS},view_range:{vr}f,block_state:{{Name:"minecraft:{block}"{p}}},'
            f'transformation:{{left_rotation:{lr},right_rotation:[0f,0f,0f,1f],translation:{V(tr)},scale:{V(sc)}}}}}')
def item(block, angle, center, scale, vr=10):
    return (f'summon minecraft:item_display ~ ~ ~ {{{TAGS},view_range:{vr}f,item:{{id:"minecraft:{block}",count:1}},item_display:"none",'
            f'transformation:{{left_rotation:{{angle:{f4(angle)},axis:[0f,1f,0f]}},right_rotation:[0f,0f,0f,1f],translation:{V(center)},scale:{V(scale)}}}}}')
OL, GR = "green_terracotta", "gray_concrete"
bomb = [
    box(OL, [-0.6,-0.6,-3.6], [0.6,0.6,2.9]), box(OL, [-0.72,-0.42,-3.5], [0.72,0.42,2.8]), box(OL, [-0.42,-0.72,-3.5], [0.42,0.72,2.8]),
    box(GR, [-0.5,-0.5,2.9], [0.5,0.5,3.6]), box(GR, [-0.36,-0.36,3.6], [0.36,0.36,4.2]), box("black_concrete", [-0.2,-0.2,4.2], [0.2,0.2,4.65]),
    box("yellow_concrete", [-0.74,-0.74,2.35], [0.74,0.74,2.65]),
    box(GR, [-0.45,-0.45,-4.2], [0.45,0.45,-3.6]), box(GR, [-0.3,-0.3,-4.65], [0.3,0.3,-4.2]),
    box(OL, [-0.03,0.6,-1.0], [0.03,0.95,0.6]), box(OL, [-0.03,-0.95,-1.0], [0.03,-0.6,0.6]),
    box(OL, [0.6,-0.03,-1.0], [0.95,0.03,0.6]), box(OL, [-0.95,-0.03,-1.0], [-0.6,0.03,0.6]),
]
EW = {"east": "true", "west": "true"}
for lo, hi in [([-0.55,0.72,-4.45], [0.55,1.85,-3.95]), ([-0.55,-1.85,-4.45], [0.55,-0.72,-3.95]),
               ([0.72,-0.55,-4.45], [1.85,0.55,-3.95]), ([-1.85,-0.55,-4.45], [-0.72,0.55,-3.95])]:
    bomb.append(box("iron_bars", lo, hi, EW))                  # решётчатые рули: вертикальные прутья
    bomb.append(box("iron_bars", lo, hi, EW, rotz90=True))     # и горизонтальные — получается сетка
K = "black_concrete"; S = math.radians(33)
bomber = [
    item(K, 0, [0,0,0], [8,1.6,17]), item("gray_concrete", 0, [0,1.1,4], [3.5,1.2,5]),
    item(K, -S, [-9.5,0,-1.8], [12,1.0,11]), item(K, S, [9.5,0,-1.8], [12,1.0,11]),
    item(K, -S, [-20,0,-8.5], [13,0.6,5]), item(K, S, [20,0,-8.5], [13,0.6,5]),
    item(K, math.radians(40), [-6.5,0,-8.8], [7,0.8,4]), item(K, -math.radians(40), [6.5,0,-8.8], [7,0.8,4]),
    item("gray_concrete", 0, [-3,0.9,1], [2,0.8,5]), item("gray_concrete", 0, [3,0.9,1], [2,0.8,5]),
]
tail = """
scoreboard players operation @e[tag=shahed_newpart] shahed_id = @s shahed_id
execute as @e[tag=shahed_newpart] run tp @s ~ ~ ~ ~ ~
tag @e[tag=shahed_newpart] remove shahed_newpart
"""
wr("bunker/build_bomb", "\n".join(bomb) + tail)
wr("bunker/build_bomber", "\n".join(bomber) + tail)

# ---------- запуск ----------
wr("bunker", """
# Бетонобойная бомба: B-2 проходит над целью и сбрасывает её туда, куда смотришь
function shahed:ray/start
function shahed:bunker/launch_common
""")
wr("bunker_strike", """
# /function shahed:bunker_strike {name:"Ник"} — пробивает грунт над игроком и рвётся в его пещере
$execute unless entity @a[name=$(name)] run return run tellraw @s {"text":"Игрок $(name) не найден","color":"red"}
kill @e[type=minecraft:marker,tag=shahed_tgt_new]
$execute at @a[name=$(name),limit=1] run summon minecraft:marker ~ ~1 ~ {Tags:["shahed_tgt_new"]}
function shahed:bunker/launch_common
""")
wr("bunker/launch_common", """
execute unless entity @e[type=minecraft:marker,tag=shahed_tgt_new] run return run tellraw @s {"text":"Цель не найдена","color":"red"}
# точка прицеливания — поверхность над целью; если цель глубже — бомба пробивается к ней
execute at @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] positioned over motion_blocking_no_leaves run summon minecraft:marker ~ ~-0.5 ~ {Tags:["shahed_tgt_surf"]}
execute store result storage shahed:tmp d.tx double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_tgt_surf,limit=1] Pos[0] 100
execute store result storage shahed:tmp d.ty double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_tgt_surf,limit=1] Pos[1] 100
execute store result storage shahed:tmp d.tz double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_tgt_surf,limit=1] Pos[2] 100
execute store result storage shahed:tmp d.gx double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] Pos[0] 100
execute store result storage shahed:tmp d.gy double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] Pos[1] 100
execute store result storage shahed:tmp d.gz double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] Pos[2] 100
execute store result score #sy shahed run data get entity @e[type=minecraft:marker,tag=shahed_tgt_surf,limit=1] Pos[1] 100
execute store result score #gy shahed run data get entity @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] Pos[1] 100
scoreboard players operation #sy shahed -= #gy shahed
data modify storage shahed:tmp d.goal set value 0b
execute if score #sy shahed matches 400.. run data modify storage shahed:tmp d.goal set value 1b
execute unless entity @e[type=minecraft:marker,tag=shahed_helper] run summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_helper"]}
execute unless entity @e[type=minecraft:marker,tag=shahed_helper2] run summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_helper2"]}
execute at @e[type=minecraft:marker,tag=shahed_tgt_surf,limit=1] rotated as @s rotated ~ 0 run function shahed:bunker/bomber_spawn_at
execute if data storage shahed:cfg {siren:1b} at @e[type=minecraft:marker,tag=shahed_tgt_surf,limit=1] run function shahed:drone/siren
kill @e[type=minecraft:marker,tag=shahed_tgt_new]
kill @e[type=minecraft:marker,tag=shahed_tgt_surf]
title @s actionbar {"text":"B-2 на боевом курсе. Сброс бетонобойной бомбы через несколько секунд.","color":"red"}
playsound minecraft:ui.button.click master @s ~ ~ ~ 1 0.5
""")
wr("bunker/bomber_spawn_at", """
execute positioned ^ ^ ^-260 if loaded ~ ~ ~ run return run function shahed:bunker/bomber_spawn
execute positioned ^ ^ ^-200 if loaded ~ ~ ~ run return run function shahed:bunker/bomber_spawn
execute positioned ^ ^ ^-150 if loaded ~ ~ ~ run return run function shahed:bunker/bomber_spawn
execute positioned ^ ^ ^-110 run function shahed:bunker/bomber_spawn
""")
wr("bunker/bomber_spawn", """
scoreboard players add #next shahed_id 1
summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_root","shahed_bomber","shahed_new"]}
execute as @e[type=minecraft:marker,tag=shahed_new,limit=1] run function shahed:bunker/bomber_init
return 1
""")
wr("bunker/bomber_init", """
tag @s remove shahed_new
data modify entity @s data set from storage shahed:tmp d
scoreboard players operation @s shahed_id = #next shahed_id
scoreboard players set @s shahed_t 0
scoreboard players set @s shahed_rel 0
scoreboard players set @s shahed_ph 0
execute store result score @s shahed_tx run data get storage shahed:tmp d.tx 10
execute store result score @s shahed_ty run data get storage shahed:tmp d.ty 10
execute store result score @s shahed_tz run data get storage shahed:tmp d.tz 10
# эшелон: 170 блоков над целью
execute store result score #y shahed run data get storage shahed:tmp d.ty 100
scoreboard players add #y shahed 17000
execute store result entity @s Pos[1] double 0.01 run scoreboard players get #y shahed
execute at @s run function shahed:drone/face with entity @s data
execute at @s run function shahed:bunker/build_bomber
""")
wr("bunker/bomber_tick", """
scoreboard players add @s shahed_t 1
function shahed:fly/measure
# сброс за ~85 блоков до цели: бомба сама доворачивает и входит почти отвесно
execute if score @s shahed_rel matches 0 if score #hd2 shahed matches ..722500 run function shahed:bunker/release
execute if score @s shahed_t matches 120.. run return run function shahed:bunker/bomber_gone
execute unless loaded ^ ^ ^40 run return run function shahed:bunker/bomber_gone
tp @s ^ ^ ^12
execute at @s run function shahed:bunker/bomber_visual
""")
wr("bunker/bomber_visual", """
scoreboard players operation #cur shahed_id = @s shahed_id
execute as @e[tag=shahed_part] if score @s shahed_id = #cur shahed_id run tp @s ~ ~ ~ ~ ~
# инверсионные следы на эшелоне
particle minecraft:cloud ^3.2 ^0.3 ^-9.5 0.15 0.15 0.15 0.003 3 force @a
particle minecraft:cloud ^-3.2 ^0.3 ^-9.5 0.15 0.15 0.15 0.003 3 force @a
scoreboard players operation #m3 shahed = @s shahed_t
scoreboard players operation #m3 shahed %= #3 shahed
execute if score #m3 shahed matches 0 run function shahed:snd/source
""")
wr("bunker/bomber_gone", """
scoreboard players operation #cur shahed_id = @s shahed_id
execute as @e[tag=shahed_part] if score @s shahed_id = #cur shahed_id run kill @s
kill @s
return 1
""")
wr("bunker/release", """
scoreboard players set @s shahed_rel 1
data modify storage shahed:tmp b set from entity @s data
data modify storage shahed:tmp brot set from entity @s Rotation
execute positioned ~ ~-4 ~ run function shahed:bunker/bomb_spawn
""")
wr("bunker/bomb_spawn", """
scoreboard players add #next shahed_id 1
summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_root","shahed_bunker","shahed_new"]}
execute as @e[type=minecraft:marker,tag=shahed_new,limit=1] at @s run function shahed:bunker/bomb_init
""")
wr("bunker/bomb_init", """
tag @s remove shahed_new
data modify entity @s data set from storage shahed:tmp b
scoreboard players operation @s shahed_id = #next shahed_id
scoreboard players set @s shahed_ph 0
scoreboard players set @s shahed_t 0
scoreboard players set @s shahed_v 600
scoreboard players set @s shahed_wp 0
scoreboard players set @s shahed_wy 0
execute store result score @s shahed_tx run data get storage shahed:tmp b.tx 10
execute store result score @s shahed_ty run data get storage shahed:tmp b.ty 10
execute store result score @s shahed_tz run data get storage shahed:tmp b.tz 10
data modify entity @s Rotation set from storage shahed:tmp brot
data modify entity @s Rotation[1] set value 10.0f
execute at @s run function shahed:bunker/build_bomb
""")

# ---------- полёт бомбы ----------
wr("bunker/tick", """
scoreboard players add @s shahed_t 1
execute if score @s shahed_ph matches 5 run return run function shahed:bunker/drill_tick
function shahed:bunker/steer with entity @s data
""")
wr("bunker/steer", """
function shahed:fly/measure
scoreboard players operation #reach shahed = @s shahed_v
scoreboard players operation #reach shahed /= #10 shahed
scoreboard players add #reach shahed 53
$execute if score #d shahed <= #reach shahed positioned $(tx) $(ty) $(tz) run return run function shahed:bunker/impact
execute if score @s shahed_t matches 300.. run return run function shahed:bunker/impact
$tp @e[type=minecraft:marker,tag=shahed_helper,sort=nearest,limit=1] ~ ~ ~ facing $(tx) $(ty) $(tz)
function shahed:fly/look
function shahed:fly/arc_cmd
scoreboard players set #W shahed 700
scoreboard players set #A shahed 80
scoreboard players add @s shahed_v 20
execute if score @s shahed_v matches 951.. run scoreboard players set @s shahed_v 950
function shahed:fly/pitch
function shahed:fly/yaw
scoreboard players set #nose shahed 465
data modify storage shahed:tmp mv.det set value "shahed:bunker/impact"
data modify storage shahed:tmp mv.vis set value "shahed:bunker/visual"
execute at @s run return run function shahed:fly/move_prep
""")
wr("bunker/visual", """
scoreboard players operation #cur shahed_id = @s shahed_id
execute as @e[tag=shahed_part] if score @s shahed_id = #cur shahed_id run tp @s ~ ~ ~ ~ ~
particle minecraft:cloud ^ ^ ^-5.2 0.08 0.08 0.08 0.004 2 force @a
# у звукового барьера — конденсационный «воротник» у головы
execute if score @s shahed_v matches 800.. run particle minecraft:white_smoke ^ ^ ^2.5 0.6 0.6 0.6 0.02 8 force @a
scoreboard players operation #m3 shahed = @s shahed_t
scoreboard players operation #m3 shahed %= #3 shahed
execute if score #m3 shahed matches 0 run function shahed:snd/source
""")

# ---------- удар и проникание ----------
wr("bunker/impact", """
tp @s ~ ~ ~
scoreboard players set @s shahed_ph 5
execute store result score @s shahed_E run data get storage shahed:cfg bunker_energy
scoreboard players set @s shahed_trav 0
scoreboard players set @s shahed_fuse -1
# отметка входного отверстия: отсюда потом ударит «вулкан»
summon minecraft:marker ~ ~1 ~ {Tags:["shahed","shahed_vent","shahed_newvent"]}
execute as @e[type=minecraft:marker,tag=shahed_newvent] at @s run function shahed:bunker/vent_init
execute if data entity @s data{goal:1b} run function shahed:bunker/aim_goal with entity @s data
execute unless data entity @s data{goal:1b} run function shahed:bunker/aim_down
function shahed:bfx/impact
return 1
""")
wr("bunker/vent_init", """
tag @s remove shahed_newvent
scoreboard players operation @s shahed_id = #cur_b shahed
function shahed:debris/sample
function shahed:fx/spray_pick
""")
s = rd("bunker/impact").replace("tp @s ~ ~ ~\n", "tp @s ~ ~ ~\nscoreboard players operation #cur_b shahed = @s shahed_id\n", 1)
wr("bunker/impact", s)
wr("bunker/aim_goal", """
$tp @s ~ ~ ~ facing $(gx) $(gy) $(gz)
execute store result score #cp shahed run data get entity @s Rotation[1] 100
execute if score #cp shahed matches ..4499 run data modify entity @s Rotation[1] set value 45.0f
""")
wr("bunker/aim_down", """
execute store result score #cp shahed run data get entity @s Rotation[1] 100
execute if score #cp shahed matches ..6999 run data modify entity @s Rotation[1] set value 70.0f
""")
wr("bunker/drill_tick", """
execute if score @s shahed_fuse matches 0.. run return run function shahed:bunker/fuse_tick
# сколько блоков за тик: чем меньше осталось энергии, тем медленнее
scoreboard players operation #n shahed = @s shahed_E
scoreboard players operation #n shahed /= #300 shahed
scoreboard players add #n shahed 1
execute if score #n shahed matches 5.. run scoreboard players set #n shahed 4
function shahed:bunker/drill_loop
execute at @s run function shahed:bunker/drill_visual
""")
wr("bunker/drill_loop", """
execute if score #n shahed matches ..0 run return 0
execute if score @s shahed_fuse matches 0.. run return 0
scoreboard players remove #n shahed 1
execute at @s positioned ^ ^ ^1 run function shahed:bunker/step
function shahed:bunker/drill_loop
""")
wr("bunker/step", """
scoreboard players set #cls shahed 8
execute if block ~ ~ ~ #shahed:passable run scoreboard players set #cls shahed 0
execute if block ~ ~ ~ #shahed:bb_soft run scoreboard players set #cls shahed 1
execute if block ~ ~ ~ #shahed:bb_rock run scoreboard players set #cls shahed 2
execute if block ~ ~ ~ #shahed:bb_deep run scoreboard players set #cls shahed 3
execute if block ~ ~ ~ #shahed:bb_hard run scoreboard players set #cls shahed 4
execute if block ~ ~ ~ #shahed:bb_vhard run scoreboard players set #cls shahed 5
execute if block ~ ~ ~ #shahed:bb_stop run scoreboard players set #cls shahed 6
execute if block ~ ~ ~ #shahed:bb_fluid run scoreboard players set #cls shahed 7
# датчик пустот: вошла в полость после ≥4 блоков породы — подрыв внутри неё
execute if score #cls shahed matches 0 if score @s shahed_trav matches 4.. run return run function shahed:bunker/void
execute if score #cls shahed matches 0 run return run tp @s ~ ~ ~
execute if score #cls shahed matches 6 run return run function shahed:bunker/stop
# сопротивление: грунт 12, порода 30, глубинный сланец 40, бетон/кирпич 110, обсидиан 300, вода 5, прочее 25
execute if score #cls shahed matches 1 run scoreboard players set #cost shahed 12
execute if score #cls shahed matches 2 run scoreboard players set #cost shahed 30
execute if score #cls shahed matches 3 run scoreboard players set #cost shahed 40
execute if score #cls shahed matches 4 run scoreboard players set #cost shahed 110
execute if score #cls shahed matches 5 run scoreboard players set #cost shahed 300
execute if score #cls shahed matches 7 run scoreboard players set #cost shahed 5
execute if score #cls shahed matches 8 run scoreboard players set #cost shahed 25
scoreboard players operation @s shahed_E -= #cost shahed
scoreboard players add @s shahed_trav 1
function shahed:bunker/carve
tp @s ~ ~ ~
execute if score @s shahed_E matches ..0 run function shahed:bunker/stop
execute if score @s shahed_trav matches 70.. run function shahed:bunker/stop
""")
wr("bunker/carve", """
fill ~-1 ~ ~ ~1 ~ ~ minecraft:air replace #shahed:drillable
fill ~ ~ ~-1 ~ ~ ~1 minecraft:air replace #shahed:drillable
fill ~ ~-1 ~ ~ ~1 ~ minecraft:air replace #shahed:drillable
particle minecraft:poof ~ ~ ~ 0.4 0.4 0.4 0.05 3 force @a
""")
wr("bunker/void", """
tp @s ~ ~ ~
execute positioned ^ ^ ^1 if block ~ ~ ~ #shahed:passable run tp @s ~ ~ ~
scoreboard players set @s shahed_fuse 3
""")
wr("bunker/stop", "scoreboard players set @s shahed_fuse 8")
wr("bunker/fuse_tick", """
scoreboard players remove @s shahed_fuse 1
execute at @s run function shahed:bunker/drill_visual
execute if score @s shahed_fuse matches ..0 run function shahed:bunker/detonate
""")
wr("bunker/drill_visual", """
scoreboard players operation #cur shahed_id = @s shahed_id
execute as @e[tag=shahed_part] if score @s shahed_id = #cur shahed_id run tp @s ~ ~ ~ ~ ~
execute as @e[type=minecraft:marker,tag=shahed_vent] if score @s shahed_id = #cur shahed_id at @s run particle minecraft:campfire_cosy_smoke ~ ~0.3 ~ 0.3 0.2 0.3 0.02 3 force @a
scoreboard players operation #m3 shahed = @s shahed_t
scoreboard players operation #m3 shahed %= #3 shahed
execute if score #m3 shahed matches 0 run playsound shahed:bb.drill ambient @a[distance=..96,scores={shahed_mode=2..}] ~ ~ ~ 6 0.85 0
execute if score #m3 shahed matches 0 run playsound minecraft:entity.warden.dig ambient @a[distance=..96,scores={shahed_mode=..1}] ~ ~ ~ 6 0.7 0
execute as @a[distance=..40] unless score @s shahed_quake matches 10.. run scoreboard players set @s shahed_quake 10
""")
wr("bunker/detonate", """
scoreboard players operation #cur shahed_id = @s shahed_id
execute as @e[tag=shahed_part] if score @s shahed_id = #cur shahed_id run kill @s
summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_bfx","shahed_newfx"]}
execute as @e[type=minecraft:marker,tag=shahed_newfx] at @s run function shahed:bfx/start
execute as @e[type=minecraft:marker,tag=shahed_vent] if score @s shahed_id = #cur shahed_id run function shahed:bfx/vent_on
kill @s
""")

# ---------- эффекты удара о поверхность ----------
wr("bfx/impact", """
# ударная волна от входа: небольшой кратер, фонтан грунта, звуковой удар + тупой удар
summon minecraft:creeper ~ ~1 ~ {Fuse:0s,ignited:1b,ExplosionRadius:2b,Silent:1b,NoAI:1b,Invulnerable:1b,CustomName:'"Бетонобойная бомба"',Tags:["shahed"]}
particle minecraft:explosion_emitter ~ ~1 ~ 0 0 0 0 1 force @a
particle minecraft:explosion ~ ~1 ~ 1 1 1 0 12 force @a
execute as @e[type=minecraft:marker,tag=shahed_vent] if score @s shahed_id = #cur_b shahed at @s run function shahed:fx/spray
stopsound @a[distance=..400] ambient
playsound shahed:bb.crack master @a[distance=..300,scores={shahed_mode=2..}] ~ ~ ~ 12 1 0.5
playsound shahed:bb.impact master @a[distance=..300,scores={shahed_mode=2..}] ~ ~ ~ 12 1 0.6
playsound minecraft:entity.firework_rocket.large_blast master @a[distance=..300,scores={shahed_mode=..1}] ~ ~ ~ 10 0.5 0.5
playsound minecraft:block.anvil.land master @a[distance=..300,scores={shahed_mode=..1}] ~ ~ ~ 8 0.5 0.5
playsound minecraft:entity.generic.explode master @a[distance=..300,scores={shahed_mode=..1}] ~ ~ ~ 8 0.6 0.5
execute as @a[distance=..60] unless score @s shahed_quake matches 20.. run scoreboard players set @s shahed_quake 20
""")

# ---------- подземный взрыв ----------
wr("bfx/start", """
tag @s remove shahed_newfx
scoreboard players set @s shahed_t 0
function shahed:debris/sample
function shahed:fx/spray_pick
execute store result score #by shahed run data get entity @s Pos[1] 100
execute positioned over motion_blocking run summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_surf","shahed_newsurf"]}
execute as @e[type=minecraft:marker,tag=shahed_newsurf] at @s run function shahed:bfx/surf_init
function shahed:bfx/cavity
function shahed:bfx/main_blast with storage shahed:cfg
function shahed:bfx/lights_on
execute as @a[distance=..70] unless predicate shahed:sky unless predicate shahed:has_nv run tag @s add shahed_nv
effect give @a[tag=shahed_nv] minecraft:night_vision 1 0 true
execute as @a[distance=..320] at @s run function shahed:bfx/seismic
""")
R_IN = [(-9,-7,4),(-6,-4,7),(-3,-1,8),(0,2,9),(3,5,8),(6,8,5)]
R_SH = [(-12,-10,5),(-9,-7,9),(-6,-4,11),(-3,-1,12),(0,2,12),(3,5,11),(6,8,10),(9,11,7)]
def oct_fill(y0, y1, w, block, repl):
    a, b = w, max(1, round(w*0.7))
    return [f"fill ~-{a} ~{y0} ~-{b} ~{a} ~{y1} ~{b} {block} replace {repl}", f"fill ~-{b} ~{y0} ~-{a} ~{b} ~{y1} ~{a} {block} replace {repl}"]
cav = ["# полость радиусом ~9 блоков (бедрок не трогаем) и зона дробления породы вокруг"]
for y0, y1, w in R_SH:
    cav += oct_fill(y0, y1, w, "minecraft:cobbled_deepslate", "minecraft:deepslate")
    cav += oct_fill(y0, y1, w, "minecraft:cobblestone", "#minecraft:stone_ore_replaceables")
for y0, y1, w in R_IN:
    cav += oct_fill(y0, y1, w, "minecraft:air", "#shahed:drillable")
cav += ["fill ~-6 ~-8 ~-6 ~6 ~-6 ~6 minecraft:gravel replace minecraft:air",
        "fill ~-2 ~-9 ~-2 ~2 ~-9 ~2 minecraft:magma_block replace #shahed:drillable",
        "fill ~4 ~-8 ~-3 ~6 ~-8 ~-1 minecraft:magma_block replace #shahed:drillable"]
cav += [f"summon minecraft:small_fireball ~{round(5*math.cos(a),1)} ~5 ~{round(5*math.sin(a),1)} {{Motion:[0.0d,-1.2d,0.0d],Tags:[\"shahed\"]}}" for a in [i*math.pi/3 for i in range(6)]]
wr("bfx/cavity", "\n".join(cav))
wr("bfx/main_blast", """
$summon minecraft:creeper ~ ~ ~ {Fuse:0s,ignited:1b,ExplosionRadius:$(bunker_power)b,Silent:1b,NoAI:1b,Invulnerable:1b,CustomName:'"Бетонобойная бомба"',Tags:["shahed"]}
""")
LIGHTS = [(0,0,0),(5,2,0),(-5,2,0),(0,2,5),(0,-4,-5),(0,5,0)]
wr("bfx/lights_on", "\n".join(f"execute positioned ~{x} ~{y} ~{z} if block ~ ~ ~ #minecraft:air run setblock ~ ~ ~ minecraft:light[level=15]" for x,y,z in LIGHTS))
wr("bfx/lights_off", "\n".join(f"execute positioned ~{x} ~{y} ~{z} if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air" for x,y,z in LIGHTS) +
   "\neffect clear @a[tag=shahed_nv] minecraft:night_vision\ntag @a remove shahed_nv")
wr("bfx/seismic", """
# сейсмическая волна в породе быстрее звука: трясёт сразу, звук придёт позже
execute positioned as @e[type=minecraft:marker,tag=shahed_bfx,sort=nearest,limit=1] if entity @s[distance=..40] unless score @s shahed_quake matches 80.. run scoreboard players set @s shahed_quake 80
execute positioned as @e[type=minecraft:marker,tag=shahed_bfx,sort=nearest,limit=1] if entity @s[distance=40..100] unless score @s shahed_quake matches 60.. run scoreboard players set @s shahed_quake 60
execute positioned as @e[type=minecraft:marker,tag=shahed_bfx,sort=nearest,limit=1] if entity @s[distance=100..] unless score @s shahed_quake matches 40.. run scoreboard players set @s shahed_quake 40
execute if entity @s[scores={shahed_mode=2..}] run playsound shahed:bb.quake master @s ~ ~ ~ 1 1 1
execute unless entity @s[scores={shahed_mode=2..}] run playsound minecraft:entity.warden.emerge master @s ~ ~ ~ 1 0.5 1
""")
wr("bfx/quake", """
scoreboard players remove @s shahed_quake 1
execute if predicate shahed:riding run return 0
scoreboard players operation #p shahed = @s shahed_quake
scoreboard players operation #p shahed %= #2 shahed
execute if score @s shahed_quake matches 50.. if score #p shahed matches 0 run tp @s ~ ~ ~ ~1.6 ~1.1
execute if score @s shahed_quake matches 50.. if score #p shahed matches 1 run tp @s ~ ~ ~ ~-1.6 ~-1.1
execute if score @s shahed_quake matches 24..49 if score #p shahed matches 0 run tp @s ~ ~ ~ ~0.9 ~0.6
execute if score @s shahed_quake matches 24..49 if score #p shahed matches 1 run tp @s ~ ~ ~ ~-0.9 ~-0.6
execute if score @s shahed_quake matches ..23 if score #p shahed matches 0 run tp @s ~ ~ ~ ~0.35 ~0.25
execute if score @s shahed_quake matches ..23 if score #p shahed matches 1 run tp @s ~ ~ ~ ~-0.35 ~-0.25
""")
wr("bfx/tick", """
scoreboard players add @s shahed_t 1
execute if score @s shahed_t matches 1..30 run function shahed:bfx/front
execute if score @s shahed_t matches 1..8 run function shahed:bfx/cave_fire
execute if score @s shahed_t matches 3 run function shahed:bfx/secondary
execute if score @s shahed_t matches 8 run function shahed:bfx/lights_off
scoreboard players operation #m shahed = @s shahed_t
scoreboard players operation #m shahed %= #2 shahed
execute if score #m shahed matches 0 if score @s shahed_t matches 2..220 run function shahed:bfx/cave_smoke with entity @s data.sp
execute if score @s shahed_t matches 12 run playsound snassets:debris/debrissettle_stonesmall0 block @a[distance=..60] ~ ~ ~ 1 0.8 1
execute if score @s shahed_t matches 30 run playsound snassets:debris/debrissettle_stonesmall2 block @a[distance=..60] ~ ~ ~ 1 0.7 1
execute if score @s shahed_t matches 300.. run kill @s
""")
wr("bfx/front", rd("mfx/front").replace("function shahed:mfx/band with storage shahed:tmp band", "function shahed:bfx/band with storage shahed:tmp band"))
wr("bfx/band", "$execute as @a[distance=$(a)..$(b)] at @s run function shahed:bfx/arrive")
wr("bfx/arrive", """
stopsound @s ambient
execute if predicate shahed:sky run return run function shahed:bfx/arrive_surface
execute if score #band shahed matches ..4 run return run function shahed:bfx/arrive_cave
function shahed:bfx/arrive_surface
""")
wr("bfx/arrive_surface", """
# на поверхности — глухой удар из-под земли
execute if entity @s[scores={shahed_mode=2..}] run playsound shahed:bb.deep master @s ~ ~ ~ 1 1 1
execute unless entity @s[scores={shahed_mode=2..}] run playsound snassets:explosions/explosion_distant_heavy master @s ~ ~ ~ 1 0.6 1
execute if score #band shahed matches ..6 run playsound minecraft:entity.lightning_bolt.thunder master @s ~ ~ ~ 0.6 0.4 0.6
execute if data storage shahed:cfg {shake:1b} if score #band shahed matches ..8 run scoreboard players set @s shahed_shake 16
""")
wr("bfx/arrive_cave", """
# под землёй рядом — взрыв в замкнутом пространстве: жёстко и с долгим эхом
execute if entity @s[scores={shahed_mode=2..}] run playsound shahed:bb.cave master @s ~ ~ ~ 1 1 1
execute if entity @s[scores={shahed_mode=2..}] run playsound shahed:blast.sub master @s ~ ~ ~ 1 0.8 1
execute unless entity @s[scores={shahed_mode=2..}] run playsound snassets:explosions/explosion_heavy1 master @s ~ ~ ~ 1 0.8 1
playsound minecraft:entity.generic.explode master @s ~ ~ ~ 1 0.5 1
playsound snassets:debris/debris_vehicle0 master @s ~ ~ ~ 1 0.7 1
execute if data storage shahed:cfg {shake:1b} if score #band shahed matches 1 run scoreboard players set @s shahed_shake 34
execute if data storage shahed:cfg {shake:1b} if score #band shahed matches 2 run scoreboard players set @s shahed_shake 30
execute if data storage shahed:cfg {shake:1b} if score #band shahed matches 3..4 run scoreboard players set @s shahed_shake 26
execute if score #band shahed matches 1..2 run function shahed:bfx/push
execute if score #band shahed matches 1 run effect give @s minecraft:darkness 4 0 true
execute if score #band shahed matches 1..2 run effect give @s minecraft:nausea 8 0 true
""")
wr("bfx/push", rd("mfx/push").replace("tag=shahed_mfx", "tag=shahed_bfx").split("\nexecute if score #band shahed matches 1 run effect")[0])
wr("bfx/cave_fire", """
particle minecraft:explosion_emitter ~ ~ ~ 3 2 3 0 3 force @a
particle minecraft:flame ~ ~ ~ 1 1 1 0.8 300 force @a
particle minecraft:lava ~ ~ ~ 4 2 4 0 40 force @a
particle minecraft:large_smoke ~ ~ ~ 5 3 5 0.1 60 force @a
execute if score @s shahed_t matches 1 run particle supplementaries:bomb_explosion_emitter ~ ~ ~ 8 0 0 1 0 force @a
execute if score @s shahed_t matches 1 run particle minecraft:flash ~ ~ ~ 2 2 2 0 30 force @a
""")
wr("bfx/secondary", """
summon minecraft:creeper ~4 ~-2 ~-3 {Fuse:0s,ignited:1b,ExplosionRadius:4b,Silent:1b,NoAI:1b,Invulnerable:1b,CustomName:'"Бетонобойная бомба"',Tags:["shahed"]}
summon minecraft:creeper ~-4 ~1 ~3 {Fuse:0s,ignited:1b,ExplosionRadius:4b,Silent:1b,NoAI:1b,Invulnerable:1b,CustomName:'"Бетонобойная бомба"',Tags:["shahed"]}
""")
wr("bfx/cave_smoke", """
# дым, пыль и каменная крошка с потолка полости
particle minecraft:large_smoke ~ ~ ~ 6 4 6 0.02 16 force @a
particle minecraft:campfire_cosy_smoke ~ ~-6 ~ 5 1 5 0.01 5 force @a
$particle minecraft:dust{color:[$(r)f,$(g)f,$(bl)f],scale:3.0f} ~ ~ ~ 7 4 7 0.01 20 force @a
$particle minecraft:falling_dust{block_state:{Name:"$(b)"}} ~ ~7 ~ 7 1 7 0 14 force @a
""")

# ---------- «вулкан» из входного отверстия ----------
wr("bfx/vent_on", """
tag @s add shahed_vent_on
scoreboard players set @s shahed_t 0
""")
jets = "\n".join(f"particle minecraft:flame ~{dx} ~0.5 ~{dz} 0 1 0 {v} 0 force @a" for dx, dz, v in [(0,0,1.3),(0.3,0,1.1),(-0.3,0.2,1.2),(0,-0.3,1.0),(0.2,0.3,1.4)])
smk = "\n".join(f"particle minecraft:large_smoke ~{dx} ~0.8 ~{dz} 0 1 0 {v} 0 force @a" for dx, dz, v in [(0,0,1.0),(0.3,-0.2,0.8),(-0.2,0.3,0.9)])
wr("bfx/vent_tick", f"""
scoreboard players add @s shahed_t 1
execute if score @s shahed_t matches 3 run function shahed:bfx/vent_burst
execute if score @s shahed_t matches 3..12 run function shahed:bfx/vent_fire
execute if score @s shahed_t matches 3..150 run function shahed:bfx/vent_smoke with entity @s data.sp
execute if score @s shahed_t matches 170.. run kill @s
""")
wr("bfx/vent_fire", jets + "\n" + smk + """
particle minecraft:lava ~ ~0.5 ~ 0.3 0.3 0.3 0 6 force @a
particle minecraft:explosion ~ ~1.5 ~ 0.3 1 0.3 0 1 force @a
""")
wr("bfx/vent_burst", """
# газы взрыва вырываются по скважине: рёв, огонь и выброс грунта вверх
playsound shahed:bb.vent master @a[distance=..160,scores={shahed_mode=2..}] ~ ~ ~ 10 1 0.4
playsound minecraft:entity.blaze.shoot master @a[distance=..160,scores={shahed_mode=..1}] ~ ~ ~ 8 0.5 0.4
playsound minecraft:block.fire.extinguish master @a[distance=..160,scores={shahed_mode=..1}] ~ ~ ~ 8 0.5 0.4
scoreboard players operation #mat shahed = @s shahed_mat
function shahed:debris/ground
function shahed:debris/ground
function shahed:debris/char
execute as @e[type=minecraft:falling_block,tag=shahed_newdeb] run function shahed:bunker/fling_vent
tag @e[tag=shahed_newdeb] remove shahed_newdeb
""")
wr("bunker/fling_vent", """
execute store result entity @s Motion[0] double 0.01 run random value -45..45
execute store result entity @s Motion[1] double 0.01 run random value 120..270
execute store result entity @s Motion[2] double 0.01 run random value -45..45
execute if data storage shahed:cfg {debris_stay:0b} run data modify entity @s CancelDrop set value 1b
""")
wr("bfx/vent_smoke", """
particle minecraft:campfire_signal_smoke ~ ~0.5 ~ 0.3 0.3 0.3 0.02 4 force @a
execute if entity @s[scores={shahed_t=..80}] run particle minecraft:large_smoke ~ ~2 ~ 0.4 1 0.4 0.08 6 force @a
$execute if entity @s[scores={shahed_t=..90}] run particle minecraft:dust{color:[$(r)f,$(g)f,$(bl)f],scale:3.5f} ~ ~1 ~ 0 1 0 0.4 0 force @a
$execute if entity @s[scores={shahed_t=..90}] run particle minecraft:dust{color:[$(r)f,$(g)f,$(bl)f],scale:3.5f} ~0.3 ~2 ~-0.2 0 1 0 0.3 0 force @a
""")

# ---------- поверхность над взрывом: вспучивание и провал ----------
wr("bfx/surf_init", """
tag @s remove shahed_newsurf
scoreboard players set @s shahed_t 0
execute store result score #sy shahed run data get entity @s Pos[1] 100
# глубина взрыва в блоках
scoreboard players operation @s shahed_E = #sy shahed
scoreboard players operation @s shahed_E -= #by shahed
scoreboard players operation @s shahed_E /= #100 shahed
# «труба» обрушения: от свода полости (взрыв + 10) до поверхности
scoreboard players operation #c shahed = #by shahed
scoreboard players add #c shahed 1000
scoreboard players operation #c shahed -= #sy shahed
scoreboard players operation #c shahed /= #100 shahed
execute if score #c shahed matches 0.. run scoreboard players set #c shahed -1
execute store result entity @s data.cy int 1 run scoreboard players get #c shahed
function shahed:debris/sample
function shahed:fx/spray_pick
""")
wr("bfx/surf_tick", """
scoreboard players add @s shahed_t 1
execute if score @s shahed_t matches 1..16 if score @s shahed_E matches ..60 run function shahed:bfx/heave_prep
execute if score @s shahed_t matches 22 if data storage shahed:cfg {collapse:1b} if score @s shahed_E matches 4..48 run function shahed:bfx/collapse with entity @s data
scoreboard players operation #m shahed = @s shahed_t
scoreboard players operation #m shahed %= #2 shahed
execute if score #m shahed matches 0 if score @s shahed_t matches 24..200 if score @s shahed_E matches ..48 run function shahed:bfx/surf_smoke with entity @s data.sp
execute if score @s shahed_t matches 240.. run kill @s
""")
wr("bfx/heave_prep", """
data modify storage shahed:tmp hv set from entity @s data.sp
scoreboard players operation #r shahed = @s shahed_t
scoreboard players operation #r shahed *= #2 shahed
execute store result storage shahed:tmp hv.rr int 1 run scoreboard players get #r shahed
function shahed:bfx/heave with storage shahed:tmp hv
""")
hv = ["# земля «подпрыгивает» расходящимся кольцом"]
for i in range(24):
    a = i * 15
    hv.append(f'$execute rotated {a} 0 positioned ^ ^ ^$(rr) positioned over motion_blocking_no_leaves run particle minecraft:block{{block_state:{{Name:"$(b)"}}}} ~ ~0.2 ~ 0.7 0.1 0.7 0.3 10 force @a')
    if i % 2 == 0:
        hv.append(f'$execute rotated {a+7} 0 positioned ^ ^ ^$(rr) positioned over motion_blocking_no_leaves run particle minecraft:dust{{color:[$(r)f,$(g)f,$(bl)f],scale:3.0f}} ~ ~0.6 ~ 1 0.3 1 0.02 6 force @a')
wr("bfx/heave", "\n".join(hv))
wr("bfx/collapse", """
# грунт над полостью проседает: гравий сыплется вниз по «трубе», на поверхности — воронка провала
$fill ~-3 ~$(cy) ~-1 ~3 ~-1 ~1 minecraft:gravel replace #shahed:drillable
$fill ~-1 ~$(cy) ~-3 ~1 ~-1 ~3 minecraft:gravel replace #shahed:drillable
$fill ~-2 ~$(cy) ~-2 ~2 ~-1 ~2 minecraft:gravel replace #shahed:drillable
fill ~-6 ~-3 ~-6 ~6 ~1 ~6 minecraft:gravel replace #shahed:bb_soil
playsound minecraft:block.gravel.break block @a ~ ~ ~ 8 0.5
playsound minecraft:block.gravel.break block @a ~ ~ ~ 8 0.7
playsound shahed:bb.quake master @a[distance=..120,scores={shahed_mode=2..}] ~ ~ ~ 8 0.8 0.4
playsound minecraft:entity.warden.emerge master @a[distance=..120,scores={shahed_mode=..1}] ~ ~ ~ 8 0.6 0.4
particle minecraft:campfire_cosy_smoke ~ ~1 ~ 4 1 4 0.02 60 force @a
execute as @a[distance=..50] unless score @s shahed_quake matches 30.. run scoreboard players set @s shahed_quake 30
""")
wr("bfx/surf_smoke", """
particle minecraft:campfire_cosy_smoke ~ ~0.5 ~ 3 0.4 3 0.01 3 force @a
$particle minecraft:dust{color:[$(r)f,$(g)f,$(bl)f],scale:3.0f} ~ ~0.8 ~ 4 0.5 4 0.01 6 force @a
""")

# ---------- справка и уборка ----------
h = rd("help")
if "bunker" not in h:
    h = h.replace('tellraw @s [{"text":"/function shahed:clear"',
        'tellraw @s [{"text":"/function shahed:bunker","color":"yellow"},{"text":" — B-2 сбрасывает бетонобойную бомбу туда, куда смотришь","color":"gray"}]\n'
        'tellraw @s [{"text":"/function shahed:bunker_strike {name:\\"Ник\\"}","color":"yellow"},{"text":" — пробить грунт над игроком и взорвать его пещеру","color":"gray"}]\n'
        'tellraw @s [{"text":"/function shahed:clear"')
    h += '\ntellraw @s [{"text":"Бомба: ","color":"gray"},{"text":"bunker_power","color":"aqua"},{"text":" (сила, 20), ","color":"gray"},{"text":"bunker_energy","color":"aqua"},{"text":" (пробивная способность, 1000 ≈ 30 блоков камня), ","color":"gray"},{"text":"collapse 0b","color":"aqua"},{"text":" — без провала грунта","color":"gray"}]'
    h += '\ntellraw @s [{"text":"/function shahed:sound_all","color":"yellow"},{"text":" — включить полные звуки всем (если у всех стоит Shahed Sounds v2)","color":"gray"}]'
    wr("help", h)
c = rd("clear")
if "shahed_quake" not in c:
    wr("clear", c.replace("scoreboard players reset * shahed_shake", "scoreboard players reset * shahed_shake\nscoreboard players reset * shahed_quake"))
print("ok")
