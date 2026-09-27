# огненный шар поднимается и превращается в грибовидную шапку
execute if score @s shahed_t matches 1..4 run particle minecraft:flame ~ ~4 ~ 3 2 3 0.06 150 force @a
execute if score @s shahed_t matches 1..4 run particle minecraft:large_smoke ~ ~5 ~ 3.5 2.5 3.5 0.05 60 force @a
execute if score @s shahed_t matches 5..9 run particle minecraft:flame ~ ~9 ~ 3.5 2 3.5 0.05 120 force @a
execute if score @s shahed_t matches 5..9 run particle minecraft:large_smoke ~ ~10 ~ 4 2.5 4 0.04 70 force @a
execute if score @s shahed_t matches 10..14 run particle minecraft:flame ~ ~14 ~ 4 2 4 0.04 90 force @a
execute if score @s shahed_t matches 10..14 run particle minecraft:large_smoke ~ ~15 ~ 5 2.5 5 0.03 80 force @a
execute if score @s shahed_t matches 15..22 run particle minecraft:flame ~ ~19 ~ 5 2 5 0.03 60 force @a
execute if score @s shahed_t matches 15..22 run particle minecraft:large_smoke ~ ~20 ~ 6 2.5 6 0.03 90 force @a
execute if score @s shahed_t matches 18..140 run particle minecraft:large_smoke ~ ~26 ~ 9 2 9 0.02 25 force @a
execute if score @s shahed_t matches 18..140 run particle minecraft:campfire_signal_smoke ~ ~24 ~ 8 1.5 8 0.01 4 force @a
execute if score @s shahed_t matches 18..45 run particle minecraft:flame ~ ~22 ~ 5 1 5 0.01 12 force @a
