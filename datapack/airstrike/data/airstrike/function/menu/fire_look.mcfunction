function airstrike:menu/prep
data remove storage airstrike:tmp sv.who
function airstrike:ray/start
data remove storage airstrike:tmp sv.sl
data modify storage airstrike:tmp sv.sl set from storage airstrike:tmp sl
execute unless entity @e[type=minecraft:marker,tag=airstrike_tgt_new] run return run tellraw @s {"text":"Цель не найдена","color":"red"}
execute at @e[type=minecraft:marker,tag=airstrike_tgt_new,limit=1] rotated as @s run function airstrike:salvo/core
kill @e[type=minecraft:marker,tag=airstrike_tgt_new]
playsound minecraft:block.note_block.bass master @s ~ ~ ~ 1 0.5
