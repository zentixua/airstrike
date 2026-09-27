function shahed:menu/prep
data remove storage shahed:tmp sv.who
function shahed:ray/start
data remove storage shahed:tmp sv.sl
data modify storage shahed:tmp sv.sl set from storage shahed:tmp sl
execute unless entity @e[type=minecraft:marker,tag=shahed_tgt_new] run return run tellraw @s {"text":"Цель не найдена","color":"red"}
execute at @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] rotated as @s run function shahed:salvo/core
kill @e[type=minecraft:marker,tag=shahed_tgt_new]
playsound minecraft:block.note_block.bass master @s ~ ~ ~ 1 0.5
