function shahed:menu/prep
data remove storage shahed:tmp sv.sl
data remove storage shahed:tmp sv.who
execute at @s run function shahed:salvo/core
playsound minecraft:block.note_block.bass master @s ~ ~ ~ 1 0.5
