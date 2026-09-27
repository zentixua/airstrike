execute store result score #a shahed run data get entity @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] Pos[0] 100
$execute store result score #b shahed run data get entity @e[tag=shahed_te$(id),limit=1] Pos[0] 100
scoreboard players operation #a shahed -= #b shahed
execute store result storage shahed:tmp sl.ox int 1 run scoreboard players get #a shahed
execute store result score #a shahed run data get entity @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] Pos[1] 100
$execute store result score #b shahed run data get entity @e[tag=shahed_te$(id),limit=1] Pos[1] 100
scoreboard players operation #a shahed -= #b shahed
execute store result storage shahed:tmp sl.oy int 1 run scoreboard players get #a shahed
execute store result score #a shahed run data get entity @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] Pos[2] 100
$execute store result score #b shahed run data get entity @e[tag=shahed_te$(id),limit=1] Pos[2] 100
scoreboard players operation #a shahed -= #b shahed
execute store result storage shahed:tmp sl.oz int 1 run scoreboard players get #a shahed
