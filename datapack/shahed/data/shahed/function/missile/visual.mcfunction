scoreboard players operation #cur shahed_id = @s shahed_id
# «живой» свет от двигателя: освещает землю под ракетой ночью
execute as @e[type=minecraft:marker,tag=shahed_lamp,distance=..32] if score @s shahed_id = #cur shahed_id at @s if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute as @e[tag=shahed_part,distance=..32] if score @s shahed_id = #cur shahed_id run tp @s ~ ~ ~ ~ ~
execute as @e[type=minecraft:marker,tag=shahed_lamp,distance=..32] if score @s shahed_id = #cur shahed_id at @s if block ~ ~ ~ #minecraft:air run setblock ~ ~ ~ minecraft:light[level=14]
particle minecraft:flame ^ ^ ^-6.1 0.06 0.06 0.06 0.01 4 force @a
particle minecraft:small_flame ^ ^ ^-6.3 0.05 0.05 0.05 0.01 3 force @a
particle minecraft:smoke ^ ^ ^-6.6 0.12 0.12 0.12 0.01 4 force @a
particle minecraft:cloud ^ ^ ^-7.4 0.04 0.04 0.04 0.003 1 force @a
particle minecraft:cloud ^ ^ ^-11 0.04 0.04 0.04 0.003 1 force @a
particle minecraft:cloud ^ ^ ^-15 0.04 0.04 0.04 0.003 1 force @a
# светящийся след (держится пару секунд) и длинный дымный шлейф
particle minecraft:end_rod ^ ^ ^-6.4 0.03 0.03 0.03 0.002 1 force @a
particle minecraft:end_rod ^ ^ ^-8.5 0.03 0.03 0.03 0.002 1 force @a
particle minecraft:end_rod ^ ^ ^-10.5 0.03 0.03 0.03 0.002 1 force @a
particle minecraft:end_rod ^ ^ ^-12.5 0.03 0.03 0.03 0.002 1 force @a
particle minecraft:end_rod ^ ^ ^-14.5 0.03 0.03 0.03 0.002 1 force @a
particle minecraft:end_rod ^ ^ ^-16.5 0.03 0.03 0.03 0.002 1 force @a
particle minecraft:campfire_cosy_smoke ^ ^ ^-7 0.05 0.05 0.05 0.003 1 force @a
particle minecraft:campfire_cosy_smoke ^ ^ ^-10 0.05 0.05 0.05 0.003 1 force @a
particle minecraft:campfire_cosy_smoke ^ ^ ^-13 0.05 0.05 0.05 0.003 1 force @a
particle minecraft:campfire_cosy_smoke ^ ^ ^-16 0.05 0.05 0.05 0.003 1 force @a
execute if score @s shahed_ph matches 1.. run particle minecraft:firework ^ ^ ^-6.3 0.05 0.05 0.05 0.03 4 force @a
execute if score @s shahed_ph matches 2 run particle minecraft:white_smoke ^ ^ ^-2 1.4 0.1 1.4 0.01 4 force @a
scoreboard players operation #m3 shahed = @s shahed_t
scoreboard players operation #m3 shahed %= #2 shahed
execute if score #m3 shahed matches 0 run function shahed:snd/source
