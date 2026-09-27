particle minecraft:campfire_signal_smoke ~ ~1 ~ 2 0.5 2 0.01 5 force @a
execute if score @s shahed_t matches 14..80 run particle minecraft:large_smoke ~ ~15 ~ 5 2 5 0.02 20 force @a
execute if score @s shahed_t matches ..60 run particle minecraft:flame ~ ~0.8 ~ 3 0.4 3 0.02 12 force @a
execute if score @s shahed_t matches ..40 run particle minecraft:lava ~ ~1 ~ 2.5 0.4 2.5 0 2 force @a
particle minecraft:smoke ~ ~1 ~ 3 0.5 3 0.02 10 force @a

execute if score @s shahed_t matches ..90 run function shahed:fx/dust_m with entity @s data.sp
