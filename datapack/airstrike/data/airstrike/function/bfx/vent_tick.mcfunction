scoreboard players add @s airstrike_t 1
execute if score @s airstrike_t matches 3 run function airstrike:bfx/vent_burst
execute if score @s airstrike_t matches 3..12 run function airstrike:bfx/vent_fire
execute if score @s airstrike_t matches 3..150 run function airstrike:bfx/vent_smoke with entity @s data.sp
execute if score @s airstrike_t matches 170.. run kill @s
