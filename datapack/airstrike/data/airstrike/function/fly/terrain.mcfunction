# максимум высоты рельефа в 15/30/45 блоках впереди (сантиблоки)
scoreboard players set #tmax airstrike -99999999
execute rotated as @s rotated ~ 0 positioned ^ ^ ^15 positioned over motion_blocking run tp @e[type=minecraft:marker,tag=airstrike_helper2,limit=1] ~ ~ ~
execute store result score #th airstrike run data get entity @e[type=minecraft:marker,tag=airstrike_helper2,limit=1] Pos[1] 100
scoreboard players operation #tmax airstrike > #th airstrike
execute rotated as @s rotated ~ 0 positioned ^ ^ ^30 positioned over motion_blocking run tp @e[type=minecraft:marker,tag=airstrike_helper2,limit=1] ~ ~ ~
execute store result score #th airstrike run data get entity @e[type=minecraft:marker,tag=airstrike_helper2,limit=1] Pos[1] 100
scoreboard players operation #tmax airstrike > #th airstrike
execute rotated as @s rotated ~ 0 positioned ^ ^ ^45 positioned over motion_blocking run tp @e[type=minecraft:marker,tag=airstrike_helper2,limit=1] ~ ~ ~
execute store result score #th airstrike run data get entity @e[type=minecraft:marker,tag=airstrike_helper2,limit=1] Pos[1] 100
scoreboard players operation #tmax airstrike > #th airstrike
