scoreboard players set #ok airstrike 0
$execute store success score #ok airstrike positioned $(px) $(py) $(pz) out_sub_level @i run tp @e[type=minecraft:marker,tag=airstrike_helper2,limit=1] ~ ~ ~
$execute unless score #ok airstrike matches 1 store success score #ok airstrike positioned $(px) $(py) $(pz) out_sub_level @n run tp @e[type=minecraft:marker,tag=airstrike_helper2,limit=1] ~ ~ ~
execute unless score #ok airstrike matches 1 run return 0
execute store result entity @s data.tx double 0.01 run data get entity @e[type=minecraft:marker,tag=airstrike_helper2,limit=1] Pos[0] 100
execute store result entity @s data.ty double 0.01 run data get entity @e[type=minecraft:marker,tag=airstrike_helper2,limit=1] Pos[1] 100
execute store result entity @s data.tz double 0.01 run data get entity @e[type=minecraft:marker,tag=airstrike_helper2,limit=1] Pos[2] 100
function airstrike:track_scores
