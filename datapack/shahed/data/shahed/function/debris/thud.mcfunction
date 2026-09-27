tag @s add shahed_thud
playsound minecraft:block.gravel.break block @a ~ ~ ~ 2 0.6
playsound minecraft:block.stone.place block @a ~ ~ ~ 1.5 0.7
particle minecraft:poof ~ ~ ~ 0.3 0.1 0.3 0.03 5 force @a
execute if entity @s[tag=shahed_hot] run particle minecraft:lava ~ ~ ~ 0.2 0.1 0.2 0 3 force @a
