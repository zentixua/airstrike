function airstrike:fx/spray_pick
function airstrike:mfx/spray_m with entity @s data.sp
execute if score @s airstrike_mat matches 10 run particle minecraft:splash ~ ~1 ~ 3 3 3 0.8 1800 force @a
execute if score @s airstrike_mat matches 10 run particle minecraft:bubble_pop ~ ~1 ~ 4 2 4 0.3 400 force @a
