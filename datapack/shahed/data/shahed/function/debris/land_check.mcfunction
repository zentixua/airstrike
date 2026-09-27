# сначала дешёвая проверка блока, NBT — только когда под обломком что-то твёрдое
execute if block ~ ~-1.2 ~ #shahed:passable run return 0
execute store result score #vy shahed run data get entity @s Motion[1] 100
execute if score #vy shahed matches ..-5 run function shahed:debris/thud
