function airstrike:mfx/lights_off
summon minecraft:creeper ~10 ~0 ~2 {Fuse:0s,ignited:1b,ExplosionRadius:3b,Silent:1b,NoAI:1b,Invulnerable:1b,CustomName:'"Крылатая ракета"',Tags:["airstrike"]}
particle minecraft:flash ~-3 ~20 ~4 0 0 0 0 1 force @a
