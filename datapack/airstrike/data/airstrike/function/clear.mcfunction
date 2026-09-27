execute as @e[type=minecraft:marker,tag=airstrike_lamp] at @s if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute as @e[type=minecraft:marker,tag=airstrike_fx] at @s run function airstrike:fx/lights_off
execute as @e[type=minecraft:marker,tag=airstrike_mfx] at @s run function airstrike:mfx/lights_off
execute as @e[type=minecraft:marker,tag=airstrike_bfx] at @s run function airstrike:bfx/lights_off
kill @e[tag=airstrike]
function airstrike:untag_all
kill @e[type=minecraft:marker,tag=airstrike_helper]
stopsound @a ambient
stopsound @a master airstrike:siren
stopsound @a master snassets:signal/siren
scoreboard players reset * airstrike_sirt
effect clear @a[tag=airstrike_nv] minecraft:night_vision
tag @a remove airstrike_nv
scoreboard players reset * airstrike_shake
scoreboard players reset * airstrike_quake
tellraw @s {"text":"Все шахеды, ракеты, бомбы и залпы убраны.","color":"gray"}
