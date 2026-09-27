execute if score @s airstrike_fuse matches 0.. run return run function airstrike:bunker/fuse_tick
# сколько блоков за тик: чем меньше осталось энергии, тем медленнее
scoreboard players operation #n airstrike = @s airstrike_E
scoreboard players operation #n airstrike /= #300 airstrike
scoreboard players add #n airstrike 1
execute if score #n airstrike matches 5.. run scoreboard players set #n airstrike 4
function airstrike:bunker/drill_loop
execute at @s run function airstrike:bunker/drill_visual
