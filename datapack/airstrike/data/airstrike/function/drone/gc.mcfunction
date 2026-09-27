scoreboard players set #clk airstrike 0
tag @e[tag=airstrike_part] add airstrike_orphan
execute as @e[type=minecraft:marker,tag=airstrike_root] run function airstrike:drone/claim
execute as @e[type=minecraft:marker,tag=airstrike_lamp,tag=airstrike_orphan] at @s if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
kill @e[tag=airstrike_orphan]
