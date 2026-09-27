scoreboard players set #clk shahed 0
tag @e[tag=shahed_part] add shahed_orphan
execute as @e[type=minecraft:marker,tag=shahed_root] run function shahed:drone/claim
execute as @e[type=minecraft:marker,tag=shahed_lamp,tag=shahed_orphan] at @s if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
kill @e[tag=shahed_orphan]
