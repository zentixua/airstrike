scoreboard players operation #cur shahed_id = @s shahed_id
tag @s add shahed_dead
execute if data entity @s data.sl.id run function shahed:untag_if_free with entity @s data.sl
execute as @e[type=minecraft:marker,tag=shahed_lamp] if score @s shahed_id = #cur shahed_id at @s if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute as @e[tag=shahed_part] if score @s shahed_id = #cur shahed_id run kill @s
summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_mfx","shahed_snd","shahed_newfx"]}
execute as @e[type=minecraft:marker,tag=shahed_newfx] at @s run function shahed:mfx/start
kill @s
return 1
