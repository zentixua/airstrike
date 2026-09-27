scoreboard players add #next shahed_id 1
summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_root","shahed_snd","shahed_new"]}
execute as @e[type=minecraft:marker,tag=shahed_new,limit=1,sort=nearest] run function shahed:drone/init
return 1
