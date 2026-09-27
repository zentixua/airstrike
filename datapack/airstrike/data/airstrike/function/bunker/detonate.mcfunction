scoreboard players operation #cur airstrike_id = @s airstrike_id
execute as @e[tag=airstrike_part] if score @s airstrike_id = #cur airstrike_id run kill @s
summon minecraft:marker ~ ~ ~ {Tags:["airstrike","airstrike_bfx","airstrike_newfx"]}
execute as @e[type=minecraft:marker,tag=airstrike_newfx] at @s run function airstrike:bfx/start
execute as @e[type=minecraft:marker,tag=airstrike_vent] if score @s airstrike_id = #cur airstrike_id run function airstrike:bfx/vent_on
kill @s
