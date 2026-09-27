execute positioned ~0 ~2 ~0 if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute positioned ~5 ~2 ~0 if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute positioned ~-5 ~2 ~0 if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute positioned ~0 ~2 ~5 if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute positioned ~0 ~2 ~-5 if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute positioned ~0 ~5 ~0 if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
effect clear @a[tag=airstrike_nv] minecraft:night_vision
tag @a remove airstrike_nv
