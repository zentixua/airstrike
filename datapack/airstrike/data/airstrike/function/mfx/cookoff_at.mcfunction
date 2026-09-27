# догорание: вторичные подрывы в воронке
$execute positioned ~$(x) ~1 ~$(z) run particle minecraft:explosion_emitter ~ ~ ~ 0 0 0 0 1 force @a
$execute positioned ~$(x) ~1 ~$(z) run particle minecraft:lava ~ ~ ~ 1 0.5 1 0 25 force @a
$execute positioned ~$(x) ~1 ~$(z) run particle minecraft:flame ~ ~ ~ 0.5 0.5 0.5 0.3 80 force @a
$execute positioned ~$(x) ~1 ~$(z) run playsound minecraft:entity.generic.explode block @a ~ ~ ~ 4 0.8
playsound snassets:explosions/bomblet_distant0 master @a[distance=..200] ~ ~ ~ 0.6 1 0.6
