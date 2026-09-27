# точка старта: позади цели по направлению взгляда, +45 по высоте; ближе, если чанк не загружен
execute positioned ^ ^45 ^-190 if loaded ~ ~ ~ run return run function airstrike:drone/spawn
execute positioned ^ ^45 ^-140 if loaded ~ ~ ~ run return run function airstrike:drone/spawn
execute positioned ^ ^45 ^-90 if loaded ~ ~ ~ run return run function airstrike:drone/spawn
execute positioned ^ ^45 ^-50 run function airstrike:drone/spawn
