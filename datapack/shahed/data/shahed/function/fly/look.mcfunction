# #los — угол на цель вниз, #dyaw — курс на цель, #cp/#cy — текущие тангаж/курс (сотые доли градуса)
execute store result score #los shahed run data get entity @e[type=minecraft:marker,tag=shahed_helper,distance=..0.01,limit=1] Rotation[1] 100
execute store result score #dyaw shahed run data get entity @e[type=minecraft:marker,tag=shahed_helper,distance=..0.01,limit=1] Rotation[0] 100
execute store result score #cp shahed run data get entity @s Rotation[1] 100
execute store result score #cy shahed run data get entity @s Rotation[0] 100
