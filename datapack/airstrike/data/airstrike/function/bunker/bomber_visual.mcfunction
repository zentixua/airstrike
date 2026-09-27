scoreboard players operation #cur airstrike_id = @s airstrike_id
execute as @e[tag=airstrike_part,distance=..48] if score @s airstrike_id = #cur airstrike_id run tp @s ~ ~ ~ ~ ~
# инверсионные следы на эшелоне
particle minecraft:cloud ^3.2 ^0.3 ^-9.5 0.15 0.15 0.15 0.003 3 force @a
particle minecraft:cloud ^-3.2 ^0.3 ^-9.5 0.15 0.15 0.15 0.003 3 force @a
scoreboard players operation #m3 airstrike = @s airstrike_t
scoreboard players operation #m3 airstrike %= #3 airstrike
execute if score #m3 airstrike matches 0 run function airstrike:snd/source
