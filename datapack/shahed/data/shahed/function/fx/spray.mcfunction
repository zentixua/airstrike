function shahed:fx/spray_pick
function shahed:fx/spray_m with entity @s data.sp
execute if score @s shahed_mat matches 10 run particle minecraft:splash ~ ~1 ~ 3 3 3 0.8 700 force @a
execute if score @s shahed_mat matches 10 run particle minecraft:bubble_pop ~ ~1 ~ 4 2 4 0.3 150 force @a
