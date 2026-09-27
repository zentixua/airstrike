scoreboard players add #next airstrike_id 1
summon minecraft:marker ~ ~ ~ {Tags:["airstrike","airstrike_root","airstrike_bomber","airstrike_snd","airstrike_new"]}
execute as @e[type=minecraft:marker,tag=airstrike_new,limit=1,sort=nearest] run function airstrike:bunker/bomber_init
return 1
