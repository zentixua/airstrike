scoreboard players add @s shahed_t 1
execute if score @s shahed_t matches 3 run function shahed:bfx/vent_burst
execute if score @s shahed_t matches 3..12 run function shahed:bfx/vent_fire
execute if score @s shahed_t matches 3..150 run function shahed:bfx/vent_smoke with entity @s data.sp
execute if score @s shahed_t matches 170.. run kill @s
