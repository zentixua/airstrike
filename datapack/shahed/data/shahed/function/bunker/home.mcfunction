# довод на цель под землёй: 30% поправки за блок
$tp @e[type=minecraft:marker,tag=shahed_helper,sort=nearest,limit=1] ~ ~ ~ facing $(gx) $(gy) $(gz)
execute store result score #hy shahed run data get entity @e[type=minecraft:marker,tag=shahed_helper,distance=..0.01,limit=1] Rotation[0] 100
execute store result score #hp shahed run data get entity @e[type=minecraft:marker,tag=shahed_helper,distance=..0.01,limit=1] Rotation[1] 100
execute store result score #cy shahed run data get entity @s Rotation[0] 100
execute store result score #cp shahed run data get entity @s Rotation[1] 100
scoreboard players operation #hy shahed -= #cy shahed
execute if score #hy shahed matches 18001.. run scoreboard players remove #hy shahed 36000
execute if score #hy shahed matches ..-18001 run scoreboard players add #hy shahed 36000
scoreboard players operation #hp shahed -= #cp shahed
scoreboard players operation #hy shahed *= #3 shahed
scoreboard players operation #hy shahed /= #10 shahed
scoreboard players operation #hp shahed *= #3 shahed
scoreboard players operation #hp shahed /= #10 shahed
scoreboard players operation #cy shahed += #hy shahed
scoreboard players operation #cp shahed += #hp shahed
execute store result entity @s Rotation[0] float 0.01 run scoreboard players get #cy shahed
execute store result entity @s Rotation[1] float 0.01 run scoreboard players get #cp shahed
