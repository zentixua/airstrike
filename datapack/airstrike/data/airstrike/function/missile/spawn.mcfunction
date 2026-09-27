scoreboard players add #next airstrike_id 1
summon minecraft:marker ~ ~ ~ {Tags:["airstrike","airstrike_root","airstrike_missile","airstrike_snd","airstrike_new"]}
execute as @e[type=minecraft:marker,tag=airstrike_new,limit=1,sort=nearest] run function airstrike:missile/init
return 1
