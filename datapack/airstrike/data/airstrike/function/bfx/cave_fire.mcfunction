particle minecraft:explosion_emitter ~ ~ ~ 3 2 3 0 3 force @a
particle minecraft:flame ~ ~ ~ 1 1 1 0.8 300 force @a
particle minecraft:lava ~ ~ ~ 4 2 4 0 40 force @a
particle minecraft:large_smoke ~ ~ ~ 5 3 5 0.1 60 force @a
execute if score @s airstrike_t matches 1 run particle supplementaries:bomb_explosion_emitter ~ ~ ~ 8 0 0 1 0 force @a
execute if score @s airstrike_t matches 1 run particle minecraft:flash ~ ~ ~ 2 2 2 0 30 force @a
