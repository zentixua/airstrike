scoreboard players add #next shahed_id 1
summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_root","shahed_bunker","shahed_snd","shahed_new"]}
execute as @e[type=minecraft:marker,tag=shahed_new,limit=1,sort=nearest] at @s run function shahed:bunker/bomb_init
