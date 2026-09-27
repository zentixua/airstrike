particle minecraft:flash ~ ~2 ~ 0.3 0.3 0.3 0 20 force @a
particle minecraft:flash ~ ~6 ~ 7 5 7 0 120 force @a
particle minecraft:flash ~ ~14 ~ 10 6 10 0 60 force @a
particle minecraft:explosion_emitter ~ ~2 ~ 4 3 4 0 10 force @a
particle minecraft:explosion ~ ~3 ~ 8 5 8 0 120 force @a
particle minecraft:flame ~ ~2 ~ 0.5 0.5 0.5 1.1 1200 force @a
particle minecraft:lava ~ ~1 ~ 4 2 4 0 200 force @a
particle minecraft:end_rod ~ ~2 ~ 0.2 0.2 0.2 1.6 300 force @a
particle minecraft:gust_emitter_large ~ ~1 ~ 2 1 2 0 4 force @a
particle minecraft:sonic_boom ~ ~3 ~ 3 2 3 0 12 force @a
execute as @a[distance=..400] unless predicate shahed:has_nv run tag @s add shahed_nv
effect give @a[tag=shahed_nv] minecraft:night_vision 2 0 true
