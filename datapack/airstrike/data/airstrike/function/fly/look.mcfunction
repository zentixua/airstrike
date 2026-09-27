# #los — угол на цель вниз, #dyaw — курс на цель, #cp/#cy — текущие тангаж/курс (сотые доли градуса)
execute store result score #los airstrike run data get entity @e[type=minecraft:marker,tag=airstrike_helper,distance=..0.01,limit=1] Rotation[1] 100
execute store result score #dyaw airstrike run data get entity @e[type=minecraft:marker,tag=airstrike_helper,distance=..0.01,limit=1] Rotation[0] 100
execute store result score #cp airstrike run data get entity @s Rotation[1] 100
execute store result score #cy airstrike run data get entity @s Rotation[0] 100
