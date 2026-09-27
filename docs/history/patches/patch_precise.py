# Точный режим по высоте + слежение за игроком, заметные следы, громкость и свист ракеты.
import os, sys
FN = sys.argv[1] + "/data/shahed/function"
def rd(n): return open(f"{FN}/{n}.mcfunction", encoding="utf-8").read()
def wr(n, t):
    p = f"{FN}/{n}.mcfunction"; os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w", encoding="utf-8").write(t.strip("\n") + "\n")
def sub(n, a, b):
    s = rd(n)
    if b in s: return
    assert a in s, (n, a[:60]); wr(n, s.replace(a, b, 1))

# ---------- 1. цель-игрок: снаряд идёт в его текущую позицию (в т.ч. в воздухе) ----------
# кто цель — в shahed:tmp who; каждый запуск переносит его в данные снаряда
for n in ("launch_common", "missile/launch_common"):
    sub(n, "execute store result storage shahed:tmp d.tx double 0.01",
        "data remove storage shahed:tmp d\ndata modify storage shahed:tmp d.who set from storage shahed:tmp who\nexecute store result storage shahed:tmp d.tx double 0.01")
for n in ("strike", "missile_strike"):
    sub(n, "kill @e[type=minecraft:marker,tag=shahed_tgt_new]\n", 'kill @e[type=minecraft:marker,tag=shahed_tgt_new]\n$data modify storage shahed:tmp who set value "$(name)"\n')
    s = rd(n).replace('summon minecraft:marker ~ ~ ~ {Tags:["shahed_tgt_new"]}', 'summon minecraft:marker ~ ~1 ~ {Tags:["shahed_tgt_new"]}')
    s = s.replace("function shahed:launch_common", "function shahed:launch_common\ndata remove storage shahed:tmp who") if n == "strike" else \
        s.replace("function shahed:missile/launch_common", "function shahed:missile/launch_common\ndata remove storage shahed:tmp who")
    wr(n, s)
for n in ("launch", "missile"):
    sub(n, "function shahed:ray/start\n", "data remove storage shahed:tmp who\nfunction shahed:ray/start\n")
wr("track", """
# цель — игрок: каждый тик берём его текущую позицию (центр тела)
$execute unless entity @a[name=$(who)] run return 0
$execute store result score #t shahed run data get entity @a[name=$(who),limit=1] Pos[0] 100
execute store result entity @s data.tx double 0.01 run scoreboard players get #t shahed
$execute store result score #t shahed run data get entity @a[name=$(who),limit=1] Pos[1] 100
scoreboard players add #t shahed 100
execute store result entity @s data.ty double 0.01 run scoreboard players get #t shahed
$execute store result score #t shahed run data get entity @a[name=$(who),limit=1] Pos[2] 100
execute store result entity @s data.tz double 0.01 run scoreboard players get #t shahed
execute store result score @s shahed_tx run data get entity @s data.tx 10
execute store result score @s shahed_ty run data get entity @s data.ty 10
execute store result score @s shahed_tz run data get entity @s data.tz 10
""")
for n in ("drone/tick", "missile/tick"):
    s = rd(n)
    if "shahed:track" not in s:
        s = s.replace("function shahed:drone/steer with entity @s data", "execute if data entity @s data.who run function shahed:track with entity @s data\nfunction shahed:drone/steer with entity @s data")
        s = s.replace("function shahed:missile/steer with entity @s data", "execute if data entity @s data.who run function shahed:track with entity @s data\nfunction shahed:missile/steer with entity @s data")
        wr(n, s)
# шахед: если цель выше — набирает высоту над ней, а не проходит снизу
sub("drone/cruise_cmd", "scoreboard players operation #H shahed > @s shahed_hc\n",
    "scoreboard players operation #H shahed > @s shahed_hc\nscoreboard players operation #t2 shahed = @s shahed_ty\nscoreboard players operation #t2 shahed *= #10 shahed\nscoreboard players add #t2 shahed 3000\nscoreboard players operation #H shahed > #t2 shahed\n")

# залп: «точно» (разброс 0) — ровно в точку, с высотой; по игроку — с слежением
sub("salvo/fire", "kill @e[type=minecraft:marker,tag=shahed_tgt_new]\n# шахеды",
    "kill @e[type=minecraft:marker,tag=shahed_tgt_new]\ndata remove storage shahed:tmp who\ndata modify storage shahed:tmp who set from entity @s data.who\n# шахеды")
sub("salvo/fire", "execute unless score @s shahed_ph matches 3 run function shahed:salvo/place with storage shahed:tmp sp2\n",
    "execute unless score @s shahed_ph matches 3 if score #r shahed matches 1.. run function shahed:salvo/place with storage shahed:tmp sp2\nexecute unless score @s shahed_ph matches 3 if score #r shahed matches 0 run summon minecraft:marker ~ ~1 ~ {Tags:[\"shahed_tgt_new\"]}\n")
sub("salvo/fire", "kill @e[type=minecraft:marker,tag=shahed_tgt_new]\n\"\"\"" if False else "scoreboard players set #nosiren shahed 0\nkill @e[type=minecraft:marker,tag=shahed_tgt_new]",
    "scoreboard players set #nosiren shahed 0\ndata remove storage shahed:tmp who\nkill @e[type=minecraft:marker,tag=shahed_tgt_new]")
sub("salvo/init", "data modify entity @s data.yaw set from storage shahed:tmp sv.yaw\n",
    "data modify entity @s data.yaw set from storage shahed:tmp sv.yaw\ndata modify entity @s data.who set from storage shahed:tmp sv.who\n")
sub("salvo/core", "data modify storage shahed:tmp sv.yaw set from entity @s Rotation[0]\n",
    "data modify storage shahed:tmp sv.yaw set from entity @s Rotation[0]\n")
for n in ("menu/fire_me", "menu/fire_look"):
    sub(n, "function shahed:menu/prep\n", "function shahed:menu/prep\ndata remove storage shahed:tmp sv.who\n")
sub("menu/fire_at", "function shahed:menu/prep\n", 'function shahed:menu/prep\n$data modify storage shahed:tmp sv.who set value "$(name)"\n')
for n in ("salvo", "salvo_look"):
    sub(n, "$data modify storage shahed:tmp sv set value", "$data modify storage shahed:tmp sv set value")

# ---------- 2. следы: видно и днём, и ночью ----------
sub("drone/visual", "# сизый выхлоп двухтактника\n", """# выхлоп: сизый шлейф днём, тусклые искры выхлопа ночью
particle minecraft:campfire_cosy_smoke ^ ^0.05 ^-3.9 0.02 0.02 0.02 0.002 1 force @a
particle minecraft:small_flame ^ ^0.05 ^-3.8 0.02 0.02 0.02 0.002 1 force @a
""")
sub("missile/visual", "particle minecraft:cloud ^ ^ ^-7.4 0.04 0.04 0.04 0.003 1 force @a\n",
    """particle minecraft:cloud ^ ^ ^-7.4 0.04 0.04 0.04 0.003 1 force @a
# светящийся след (держится пару секунд) и длинный дымный шлейф
particle minecraft:end_rod ^ ^ ^-6.4 0.03 0.03 0.03 0.002 2 force @a
particle minecraft:campfire_cosy_smoke ^ ^ ^-7.0 0.05 0.05 0.05 0.003 2 force @a
""")
sub("bunker/visual", "particle minecraft:cloud ^ ^ ^-5.2 0.08 0.08 0.08 0.004 2 force @a\n",
    "particle minecraft:cloud ^ ^ ^-5.2 0.08 0.08 0.08 0.004 3 force @a\nparticle minecraft:campfire_cosy_smoke ^ ^ ^-5.5 0.05 0.05 0.05 0.002 1 force @a\n")

# ---------- 3. громкость: моторы громче, сирены дальше ----------
s = rd("snd/listener")
s = s.replace("execute if score #kind shahed matches 1 run scoreboard players set #g shahed 30000", "execute if score #kind shahed matches 1 run scoreboard players set #g shahed 60000")
s = s.replace("execute if score #kind shahed matches 2 run scoreboard players set #g shahed 45000", "execute if score #kind shahed matches 2 run scoreboard players set #g shahed 90000")
s = s.replace("execute if score #g shahed matches ..5 run scoreboard players set #g shahed 6", "execute if score #g shahed matches ..11 run scoreboard players set #g shahed 12")
if "snd/whistle" not in s:
    s = s.replace("execute if score @s shahed_mode matches 1.. if score #kind shahed matches ..2 run scoreboard players set #cm shahed 1",
                  "execute if score #kind shahed matches 2 if score #wd shahed matches ..1600 run function shahed:snd/whistle\nexecute if score @s shahed_mode matches 1.. if score #kind shahed matches ..2 run scoreboard players set #cm shahed 1")
wr("snd/listener", s)
s = rd("snd/source")
if "#wd" not in s:
    s = s.replace("scoreboard players set #nodop shahed 0\n", "scoreboard players set #nodop shahed 0\nscoreboard players operation #wd shahed = #d shahed\n")
    wr("snd/source", s)
for n in ("snd/tail_drone", "snd/tail_missile"):
    s = rd(n)
    if "#wd" not in s:
        wr(n, s.replace("scoreboard players set #nodop shahed 1\n", "scoreboard players set #nodop shahed 1\nscoreboard players set #wd shahed 99999\n"))
wr("snd/whistle", """
# свист на подлёте последних ~160 блоков: чем ближе к цели, тем ниже тон; громкость — по расстоянию до слушателя
scoreboard players operation #wp shahed = #wd shahed
scoreboard players operation #wp shahed *= #140 shahed
scoreboard players operation #wp shahed /= #1600 shahed
scoreboard players add #wp shahed 60
execute if score #wp shahed matches 201.. run scoreboard players set #wp shahed 200
scoreboard players set #wg shahed 110000
scoreboard players operation #wg shahed /= #ld shahed
execute if score #wg shahed matches ..19 run scoreboard players set #wg shahed 20
execute if score #wg shahed matches 101.. run scoreboard players set #wg shahed 100
execute store result storage shahed:tmp ws.p double 0.01 run scoreboard players get #wp shahed
execute store result storage shahed:tmp ws.g double 0.01 run scoreboard players get #wg shahed
stopsound @s ambient snassets:weapons/bomb_whistle
function shahed:snd/whistle_m with storage shahed:tmp ws
""")
wr("snd/whistle_m", "$playsound snassets:weapons/bomb_whistle ambient @s ~ ~ ~ $(g) $(p) 0")
t = rd("load")
if "#140 shahed" not in t:
    wr("load", t + "scoreboard players set #140 shahed 140\nscoreboard players set #1600 shahed 1600\n")
for n, a1, a2 in (("drone/siren", ("~70 ~12 ~50", "~-85 ~12 ~-45"), ("~115 ~15 ~80", "~-130 ~15 ~-70")),
                  ("missile/siren", ("~80 ~12 ~-40", "~-60 ~12 ~75"), ("~125 ~15 ~-65", "~-95 ~15 ~115"))):
    s = rd(n)
    for a, b in zip(a1, a2): s = s.replace(a, b)
    wr(n, s)
print("ok")

# залп с разбросом вокруг цели в воздухе (самолёт из Create, полёт): точки на той же высоте, а не на земле под ней
sub("salvo/fire", "execute unless score @s shahed_ph matches 3 if score #r shahed matches 1.. run function shahed:salvo/place with storage shahed:tmp sp2\n",
    "scoreboard players set #air shahed 0\nexecute if block ~ ~-1 ~ #shahed:passable if block ~ ~-2 ~ #shahed:passable if block ~ ~-3 ~ #shahed:passable run scoreboard players set #air shahed 1\n"
    "execute unless score @s shahed_ph matches 3 if score #r shahed matches 1.. if score #air shahed matches 0 run function shahed:salvo/place with storage shahed:tmp sp2\n"
    "execute unless score @s shahed_ph matches 3 if score #r shahed matches 1.. if score #air shahed matches 1 run function shahed:salvo/place_air with storage shahed:tmp sp2\n")
wr("salvo/place_air", '$execute positioned ~$(x) ~1 ~$(z) run summon minecraft:marker ~ ~ ~ {Tags:["shahed_tgt_new"]}')
print("ok2")
