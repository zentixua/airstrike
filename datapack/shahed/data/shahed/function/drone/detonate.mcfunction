scoreboard players operation #cur shahed_id = @s shahed_id
tag @s add shahed_dead
execute if data entity @s data.sl.id run function shahed:untag_if_free with entity @s data.sl
execute as @e[tag=shahed_part] if score @s shahed_id = #cur shahed_id run kill @s
summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_fx","shahed_snd","shahed_newfx"]}
execute as @e[type=minecraft:marker,tag=shahed_newfx] at @s run function shahed:fx/start
kill @s
return 1
