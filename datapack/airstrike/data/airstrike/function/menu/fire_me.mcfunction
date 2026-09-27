function airstrike:menu/prep
data remove storage airstrike:tmp sv.sl
data remove storage airstrike:tmp sv.who
execute at @s run function airstrike:salvo/core
playsound minecraft:block.note_block.bass master @s ~ ~ ~ 1 0.5
