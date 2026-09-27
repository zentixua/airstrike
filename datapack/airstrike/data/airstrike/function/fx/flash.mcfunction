# вспышка: частицы + ночное зрение на пару тиков (мир резко светлеет) + свет от light-блоков
particle minecraft:flash ~ ~1.5 ~ 0.2 0.2 0.2 0 10 force @a
particle minecraft:flash ~ ~5 ~ 5 4 5 0 60 force @a
particle minecraft:explosion_emitter ~ ~1 ~ 2 1.5 2 0 5 force @a
particle minecraft:explosion ~ ~2 ~ 5 3 5 0 60 force @a
particle minecraft:flame ~ ~1.5 ~ 0.3 0.3 0.3 0.7 600 force @a
particle minecraft:lava ~ ~1 ~ 2 1 2 0 80 force @a
particle minecraft:end_rod ~ ~1.5 ~ 0.1 0.1 0.1 1.1 160 force @a
particle minecraft:gust_emitter_large ~ ~1 ~ 0 0 0 0 1 force @a
particle minecraft:sonic_boom ~ ~2 ~ 1.5 1 1.5 0 6 force @a
execute as @a[distance=..240] unless predicate airstrike:has_nv run tag @s add airstrike_nv
effect give @a[tag=airstrike_nv] minecraft:night_vision 1 0 true
