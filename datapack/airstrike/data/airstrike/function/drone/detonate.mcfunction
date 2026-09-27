scoreboard players operation #cur airstrike_id = @s airstrike_id
tag @s add airstrike_dead
execute if data entity @s data.sl.id run function airstrike:untag_if_free with entity @s data.sl
execute as @e[tag=airstrike_part] if score @s airstrike_id = #cur airstrike_id run kill @s
summon minecraft:marker ~ ~ ~ {Tags:["airstrike","airstrike_fx","airstrike_snd","airstrike_newfx"]}
execute as @e[type=minecraft:marker,tag=airstrike_newfx] at @s run function airstrike:fx/start
kill @s
return 1
