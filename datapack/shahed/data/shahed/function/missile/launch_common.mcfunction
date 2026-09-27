execute unless entity @e[type=minecraft:marker,tag=shahed_tgt_new] run return run tellraw @s {"text":"Цель не найдена","color":"red"}
data remove storage shahed:tmp d
data modify storage shahed:tmp d.who set from storage shahed:tmp who
data modify storage shahed:tmp d.sl set from storage shahed:tmp sl
data modify storage shahed:tmp d.wo set from storage shahed:tmp wo
execute store result storage shahed:tmp d.tx double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] Pos[0] 100
execute store result storage shahed:tmp d.ty double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] Pos[1] 100
execute store result storage shahed:tmp d.tz double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] Pos[2] 100
execute unless entity @e[type=minecraft:marker,tag=shahed_helper] run summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_helper"]}
execute unless entity @e[type=minecraft:marker,tag=shahed_helper2] run summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_helper2"]}
execute at @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] rotated as @s rotated ~ 0 run function shahed:missile/launch_spawn
execute if data storage shahed:cfg {siren:1b} unless score #nosiren shahed matches 1 at @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] run function shahed:missile/siren
kill @e[type=minecraft:marker,tag=shahed_tgt_new]
title @s actionbar {"text":"Крылатая ракета пущена. Подлёт через ~7 секунд.","color":"red"}
playsound minecraft:ui.button.click master @s ~ ~ ~ 1 0.5
