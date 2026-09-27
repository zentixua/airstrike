execute if score @s shahed_fuse matches 0.. run return run function shahed:bunker/fuse_tick
# сколько блоков за тик: чем меньше осталось энергии, тем медленнее
scoreboard players operation #n shahed = @s shahed_E
scoreboard players operation #n shahed /= #300 shahed
scoreboard players add #n shahed 1
execute if score #n shahed matches 5.. run scoreboard players set #n shahed 4
function shahed:bunker/drill_loop
execute at @s run function shahed:bunker/drill_visual
