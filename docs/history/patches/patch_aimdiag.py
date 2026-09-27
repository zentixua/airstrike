# Тихая диагностика прицела (одна серая строка у стрелка), чтобы разбирать проблемы по логу, не отвлекая игроков.
import os, sys
FN = sys.argv[1] + "/data/shahed/function"
def rd(n): return open(f"{FN}/{n}.mcfunction", encoding="utf-8").read()
def wr(n, t): open(f"{FN}/{n}.mcfunction", "w", encoding="utf-8").write(t.strip("\n") + "\n")
s = rd("ray/start")
s = s.replace("execute centered_in_sub_level @e run scoreboard players set #sln shahed 1\n",
              "scoreboard players set #cnt shahed 0\nexecute centered_in_sub_level @e run scoreboard players add #cnt shahed 1\nexecute if score #cnt shahed matches 1.. run scoreboard players set #sln shahed 1\n"
              "scoreboard players set #vv shahed 0\nexecute if score #sln shahed matches 1 at @s centered_in_sub_level @v run scoreboard players set #vv shahed 1\n")
if "ray/diag" not in s:
    s += "\nexecute if score #sln shahed matches 1 if data storage shahed:cfg {aimdiag:1b} run function shahed:ray/diag"
wr("ray/start", s)
wr("ray/diag", """
# что видит прицел: сколько аппаратов, выбран ли «@v», чем кончился луч, где ближайший аппарат и работает ли перевод координат
scoreboard players set #rk shahed 3
execute if data storage shahed:tmp sl.px run scoreboard players set #rk shahed 1
execute if data storage shahed:tmp sl.id run scoreboard players set #rk shahed 2
execute if score #steps shahed matches 400.. run scoreboard players set #rk shahed 4
execute at @s centered_in_sub_level @n out_sub_level @n run summon minecraft:marker ~ ~ ~ {Tags:["shahed_dbg"]}
scoreboard players set #nd shahed -1
execute if entity @e[type=minecraft:marker,tag=shahed_dbg] run function shahed:ray/diag_dist
scoreboard players set #ins shahed 0
execute at @e[type=minecraft:marker,tag=shahed_dbg,limit=1] in_sub_level @n run scoreboard players set #ins shahed 1
execute at @e[type=minecraft:marker,tag=shahed_dbg,limit=1] in_sub_level @n unless block ~ ~ ~ #shahed:passable run scoreboard players set #ins shahed 2
tellraw @s ["",{"text":"[прицел] аппаратов ","color":"dark_gray"},{"score":{"name":"#cnt","objective":"shahed"},"color":"gray"},{"text":" · взгляд ","color":"dark_gray"},{"score":{"name":"#vv","objective":"shahed"},"color":"gray"},{"text":" · итог ","color":"dark_gray"},{"score":{"name":"#rk","objective":"shahed"},"color":"gray"},{"text":" · шагов ","color":"dark_gray"},{"score":{"name":"#steps","objective":"shahed"},"color":"gray"},{"text":" · до ближ. ","color":"dark_gray"},{"score":{"name":"#nd","objective":"shahed"},"color":"gray"},{"text":" · в центре ","color":"dark_gray"},{"score":{"name":"#ins","objective":"shahed"},"color":"gray"},{"text":" ","color":"dark_gray"},{"entity":"@e[type=minecraft:marker,tag=shahed_dbg,limit=1]","nbt":"Pos","color":"dark_gray"}]
kill @e[type=minecraft:marker,tag=shahed_dbg]
""")
wr("ray/diag_dist", """
execute store result score #a shahed run data get entity @e[type=minecraft:marker,tag=shahed_dbg,limit=1] Pos[0]
execute store result score #b shahed run data get entity @s Pos[0]
scoreboard players operation #a shahed -= #b shahed
scoreboard players operation #a shahed *= #a shahed
execute store result score #c shahed run data get entity @e[type=minecraft:marker,tag=shahed_dbg,limit=1] Pos[2]
execute store result score #b shahed run data get entity @s Pos[2]
scoreboard players operation #c shahed -= #b shahed
scoreboard players operation #c shahed *= #c shahed
scoreboard players operation #a shahed += #c shahed
scoreboard players operation #nd shahed = #a shahed
""")
t = rd("load")
if "aimdiag" not in t:
    wr("load", t + "execute unless data storage shahed:cfg aimdiag run data modify storage shahed:cfg aimdiag set value 0b\n")
print("ok")
