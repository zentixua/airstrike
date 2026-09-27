particle minecraft:campfire_signal_smoke ~ ~1 ~ 3.5 0.6 3.5 0.01 8 force @a
particle minecraft:smoke ~ ~1 ~ 5 0.6 5 0.02 16 force @a
execute if score @s airstrike_t matches ..100 run particle minecraft:flame ~ ~0.8 ~ 5 0.5 5 0.02 20 force @a
execute if score @s airstrike_t matches ..60 run particle minecraft:lava ~ ~1 ~ 4 0.5 4 0 4 force @a
execute if score @s airstrike_t matches ..200 run particle minecraft:large_smoke ~ ~10 ~ 3 6 3 0.03 10 force @a

execute if score @s airstrike_t matches ..90 run function airstrike:mfx/dust_m with entity @s data.sp
