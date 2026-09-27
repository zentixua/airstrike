scoreboard players operation #cur shahed_id = @s shahed_id
execute as @e[tag=shahed_part] if score @s shahed_id = #cur shahed_id run kill @s
summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_bfx","shahed_newfx"]}
execute as @e[type=minecraft:marker,tag=shahed_newfx] at @s run function shahed:bfx/start
execute as @e[type=minecraft:marker,tag=shahed_vent] if score @s shahed_id = #cur shahed_id run function shahed:bfx/vent_on
kill @s
