# довод на цель под землёй: 30% поправки за блок
$tp @e[type=minecraft:marker,tag=airstrike_helper,sort=nearest,limit=1] ~ ~ ~ facing $(gx) $(gy) $(gz)
execute store result score #hy airstrike run data get entity @e[type=minecraft:marker,tag=airstrike_helper,distance=..0.01,limit=1] Rotation[0] 100
execute store result score #hp airstrike run data get entity @e[type=minecraft:marker,tag=airstrike_helper,distance=..0.01,limit=1] Rotation[1] 100
execute store result score #cy airstrike run data get entity @s Rotation[0] 100
execute store result score #cp airstrike run data get entity @s Rotation[1] 100
scoreboard players operation #hy airstrike -= #cy airstrike
execute if score #hy airstrike matches 18001.. run scoreboard players remove #hy airstrike 36000
execute if score #hy airstrike matches ..-18001 run scoreboard players add #hy airstrike 36000
scoreboard players operation #hp airstrike -= #cp airstrike
scoreboard players operation #hy airstrike *= #3 airstrike
scoreboard players operation #hy airstrike /= #10 airstrike
scoreboard players operation #hp airstrike *= #3 airstrike
scoreboard players operation #hp airstrike /= #10 airstrike
scoreboard players operation #cy airstrike += #hy airstrike
scoreboard players operation #cp airstrike += #hp airstrike
execute store result entity @s Rotation[0] float 0.01 run scoreboard players get #cy airstrike
execute store result entity @s Rotation[1] float 0.01 run scoreboard players get #cp airstrike
