execute if entity @s[tag=airstrike_hot] run particle minecraft:flame ~ ~0.5 ~ 0.1 0.1 0.1 0.01 1 force @a
execute if entity @s[tag=airstrike_hot] run particle minecraft:large_smoke ~ ~0.5 ~ 0.1 0.1 0.1 0.01 1 force @a
execute unless entity @s[tag=airstrike_hot] run particle minecraft:smoke ~ ~0.5 ~ 0.05 0.05 0.05 0.01 1 force @a
execute if entity @s[tag=!airstrike_thud] run function airstrike:debris/land_check
