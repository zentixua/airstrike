# этот игрок слышал сирену меньше 13 с назад — вторую поверх не включаем
scoreboard players operation #dt airstrike = #now airstrike
scoreboard players operation #dt airstrike -= @s airstrike_sirt
execute if score #dt airstrike matches 0..259 run return 0
scoreboard players operation @s airstrike_sirt = #now airstrike
tag @s add airstrike_sir
