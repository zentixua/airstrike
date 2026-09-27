execute positioned ~0 ~2 ~0 if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute positioned ~9 ~3 ~0 if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute positioned ~-9 ~3 ~0 if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute positioned ~0 ~3 ~9 if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute positioned ~0 ~3 ~-9 if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute positioned ~0 ~10 ~0 if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute positioned ~7 ~7 ~7 if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute positioned ~-7 ~7 ~-7 if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute positioned ~7 ~7 ~-7 if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute positioned ~-7 ~7 ~7 if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute positioned ~13 ~4 ~5 if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute positioned ~-13 ~4 ~-5 if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute positioned ~0 ~18 ~0 if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
effect clear @a[tag=airstrike_nv] minecraft:night_vision
tag @a remove airstrike_nv
