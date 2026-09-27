execute as @e[type=minecraft:marker,tag=shahed_lamp] at @s if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute as @e[type=minecraft:marker,tag=shahed_fx] at @s run function shahed:fx/lights_off
execute as @e[type=minecraft:marker,tag=shahed_mfx] at @s run function shahed:mfx/lights_off
execute as @e[type=minecraft:marker,tag=shahed_bfx] at @s run function shahed:bfx/lights_off
kill @e[tag=shahed]
function shahed:untag_all
kill @e[type=minecraft:marker,tag=shahed_helper]
stopsound @a ambient
stopsound @a master shahed:siren
stopsound @a master snassets:signal/siren
scoreboard players reset * shahed_sirt
effect clear @a[tag=shahed_nv] minecraft:night_vision
tag @a remove shahed_nv
scoreboard players reset * shahed_shake
scoreboard players reset * shahed_quake
tellraw @s {"text":"Все шахеды, ракеты, бомбы и залпы убраны.","color":"gray"}
