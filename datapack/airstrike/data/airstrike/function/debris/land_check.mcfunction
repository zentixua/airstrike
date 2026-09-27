# сначала дешёвая проверка блока, NBT — только когда под обломком что-то твёрдое
execute if block ~ ~-1.2 ~ #airstrike:passable run return 0
execute store result score #vy airstrike run data get entity @s Motion[1] 100
execute if score #vy airstrike matches ..-5 run function airstrike:debris/thud
