execute unless entity @e[type=minecraft:marker,tag=airstrike_tgt_new] run return run tellraw @s {"text":"Цель не найдена","color":"red"}
data remove storage airstrike:tmp d
data modify storage airstrike:tmp d.who set from storage airstrike:tmp who
data modify storage airstrike:tmp d.sl set from storage airstrike:tmp sl
data modify storage airstrike:tmp d.wo set from storage airstrike:tmp wo
execute store result storage airstrike:tmp d.tx double 0.01 run data get entity @e[type=minecraft:marker,tag=airstrike_tgt_new,limit=1] Pos[0] 100
execute store result storage airstrike:tmp d.ty double 0.01 run data get entity @e[type=minecraft:marker,tag=airstrike_tgt_new,limit=1] Pos[1] 100
execute store result storage airstrike:tmp d.tz double 0.01 run data get entity @e[type=minecraft:marker,tag=airstrike_tgt_new,limit=1] Pos[2] 100
execute unless entity @e[type=minecraft:marker,tag=airstrike_helper] run summon minecraft:marker ~ ~ ~ {Tags:["airstrike","airstrike_helper"]}
execute unless entity @e[type=minecraft:marker,tag=airstrike_helper2] run summon minecraft:marker ~ ~ ~ {Tags:["airstrike","airstrike_helper2"]}
execute at @e[type=minecraft:marker,tag=airstrike_tgt_new,limit=1] rotated as @s rotated ~ 0 run function airstrike:launch_spawn
execute if data storage airstrike:cfg {siren:1b} unless score #nosiren airstrike matches 1 at @e[type=minecraft:marker,tag=airstrike_tgt_new,limit=1] run function airstrike:drone/siren
kill @e[type=minecraft:marker,tag=airstrike_tgt_new]
title @s actionbar {"text":"Шахед в воздухе. Цель помечена.","color":"red"}
playsound minecraft:ui.button.click master @s ~ ~ ~ 1 0.6
