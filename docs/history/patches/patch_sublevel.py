# Прицел по летательным аппаратам Create Aeronautics (Sable): камерой на аппарат — удар в него, с слежением.
import os, sys
FN = sys.argv[1] + "/data/shahed/function"
def rd(n): return open(f"{FN}/{n}.mcfunction", encoding="utf-8").read()
def wr(n, t):
    p = f"{FN}/{n}.mcfunction"; os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w", encoding="utf-8").write(t.strip("\n") + "\n")
def sub(n, a, b):
    s = rd(n)
    if b in s: return
    assert a in s, (n, a[:70]); wr(n, s.replace(a, b, 1))

# 1) прицел: сначала аппарат под взглядом (до 100 блоков), иначе обычный луч по блокам
wr("ray/start", """
kill @e[type=minecraft:marker,tag=shahed_tgt_new]
data remove storage shahed:tmp sl
function shahed:ray/sublevel
execute if entity @e[type=minecraft:marker,tag=shahed_tgt_new] run data modify storage shahed:tmp sl set value 1b
execute if entity @e[type=minecraft:marker,tag=shahed_tgt_new] run return run title @s actionbar {"text":"Цель: летательный аппарат — снаряд пойдёт за ним","color":"gold"}
scoreboard players set #steps shahed 0
execute at @s anchored eyes positioned ^ ^ ^0.5 run function shahed:ray/step
""")
wr("ray/sublevel", """
# аппараты Sable живут в своём пространстве: берём центр того, на который смотрит игрок, в мировых координатах
execute at @s centered_in_sub_level @v out_sub_level @v run summon minecraft:marker ~ ~ ~ {Tags:["shahed_tgt_new"]}
""")
# 2) слежение за аппаратом в полёте снаряда
wr("track_sl", """
$execute store success score #ok shahed positioned $(tx) $(ty) $(tz) run function shahed:track_sl_find
execute unless score #ok shahed matches 1 run return 0
execute store result entity @s data.tx double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_helper2,limit=1] Pos[0] 100
execute store result entity @s data.ty double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_helper2,limit=1] Pos[1] 100
execute store result entity @s data.tz double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_helper2,limit=1] Pos[2] 100
execute store result score @s shahed_tx run data get entity @s data.tx 10
execute store result score @s shahed_ty run data get entity @s data.ty 10
execute store result score @s shahed_tz run data get entity @s data.tz 10
""")
wr("track_sl_find", """
scoreboard players set #ok shahed 0
execute centered_in_sub_level @n[distance=..48] out_sub_level @n[distance=..48] run tp @e[type=minecraft:marker,tag=shahed_helper2,limit=1] ~ ~ ~
execute centered_in_sub_level @n[distance=..48] out_sub_level @n[distance=..48] run scoreboard players set #ok shahed 1
return run scoreboard players get #ok shahed
""")
s = rd("track_sl").replace("$execute store success score #ok shahed positioned $(tx) $(ty) $(tz) run function shahed:track_sl_find",
                           "$execute positioned $(tx) $(ty) $(tz) run function shahed:track_sl_find")
wr("track_sl", s)
for n, st in (("drone/tick", "function shahed:drone/steer with entity @s data"), ("missile/tick", "function shahed:missile/steer with entity @s data")):
    sub(n, st, "execute if data entity @s data.sl run function shahed:track_sl with entity @s data\n" + st)
# 3) признак «цель — аппарат» проходит через все пути запуска
for n in ("launch_common", "missile/launch_common"):
    sub(n, "data modify storage shahed:tmp d.who set from storage shahed:tmp who\n",
        "data modify storage shahed:tmp d.who set from storage shahed:tmp who\ndata modify storage shahed:tmp d.sl set from storage shahed:tmp sl\n")
for n in ("strike", "missile_strike"):
    sub(n, "kill @e[type=minecraft:marker,tag=shahed_tgt_new]\n", "kill @e[type=minecraft:marker,tag=shahed_tgt_new]\ndata remove storage shahed:tmp sl\n")
for n in ("menu/fire_look", "salvo_look"):
    sub(n, "function shahed:ray/start\n", "function shahed:ray/start\ndata modify storage shahed:tmp sv.sl set from storage shahed:tmp sl\n")
for n in ("menu/fire_me", "menu/fire_at"):
    sub(n, "function shahed:menu/prep\n", "function shahed:menu/prep\ndata remove storage shahed:tmp sv.sl\n")
sub("salvo", "execute at @s run function shahed:salvo/begin", "data remove storage shahed:tmp sl\nexecute at @s run function shahed:salvo/begin")
sub("salvo/init", "data modify entity @s data.who set from storage shahed:tmp sv.who\n",
    "data modify entity @s data.who set from storage shahed:tmp sv.who\ndata modify entity @s data.sl set from storage shahed:tmp sv.sl\n")
sub("salvo/fire", "execute if score #r shahed matches 0 run data modify storage shahed:tmp who set from entity @s data.who\n",
    "execute if score #r shahed matches 0 run data modify storage shahed:tmp who set from entity @s data.who\ndata remove storage shahed:tmp sl\nexecute if score #r shahed matches 0 run data modify storage shahed:tmp sl set from entity @s data.sl\n")
sub("salvo/fire", "scoreboard players set #nosiren shahed 0\ndata remove storage shahed:tmp who\n",
    "scoreboard players set #nosiren shahed 0\ndata remove storage shahed:tmp who\ndata remove storage shahed:tmp sl\n")
print("ok")
