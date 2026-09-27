scoreboard players set #ok shahed 0
$execute store success score #ok shahed positioned $(px) $(py) $(pz) out_sub_level @i run tp @e[type=minecraft:marker,tag=shahed_helper2,limit=1] ~ ~ ~
$execute unless score #ok shahed matches 1 store success score #ok shahed positioned $(px) $(py) $(pz) out_sub_level @n run tp @e[type=minecraft:marker,tag=shahed_helper2,limit=1] ~ ~ ~
execute unless score #ok shahed matches 1 run return 0
execute store result entity @s data.tx double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_helper2,limit=1] Pos[0] 100
execute store result entity @s data.ty double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_helper2,limit=1] Pos[1] 100
execute store result entity @s data.tz double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_helper2,limit=1] Pos[2] 100
function shahed:track_scores
