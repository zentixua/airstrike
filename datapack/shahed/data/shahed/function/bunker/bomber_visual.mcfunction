scoreboard players operation #cur shahed_id = @s shahed_id
execute as @e[tag=shahed_part,distance=..48] if score @s shahed_id = #cur shahed_id run tp @s ~ ~ ~ ~ ~
# инверсионные следы на эшелоне
particle minecraft:cloud ^3.2 ^0.3 ^-9.5 0.15 0.15 0.15 0.003 3 force @a
particle minecraft:cloud ^-3.2 ^0.3 ^-9.5 0.15 0.15 0.15 0.003 3 force @a
scoreboard players operation #m3 shahed = @s shahed_t
scoreboard players operation #m3 shahed %= #3 shahed
execute if score #m3 shahed matches 0 run function shahed:snd/source
