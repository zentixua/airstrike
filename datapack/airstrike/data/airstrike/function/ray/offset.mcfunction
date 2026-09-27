execute store result score #a airstrike run data get entity @e[type=minecraft:marker,tag=airstrike_tgt_new,limit=1] Pos[0] 100
$execute store result score #b airstrike run data get entity @e[tag=airstrike_te$(id),limit=1] Pos[0] 100
scoreboard players operation #a airstrike -= #b airstrike
execute store result storage airstrike:tmp sl.ox int 1 run scoreboard players get #a airstrike
execute store result score #a airstrike run data get entity @e[type=minecraft:marker,tag=airstrike_tgt_new,limit=1] Pos[1] 100
$execute store result score #b airstrike run data get entity @e[tag=airstrike_te$(id),limit=1] Pos[1] 100
scoreboard players operation #a airstrike -= #b airstrike
execute store result storage airstrike:tmp sl.oy int 1 run scoreboard players get #a airstrike
execute store result score #a airstrike run data get entity @e[type=minecraft:marker,tag=airstrike_tgt_new,limit=1] Pos[2] 100
$execute store result score #b airstrike run data get entity @e[tag=airstrike_te$(id),limit=1] Pos[2] 100
scoreboard players operation #a airstrike -= #b airstrike
execute store result storage airstrike:tmp sl.oz int 1 run scoreboard players get #a airstrike
