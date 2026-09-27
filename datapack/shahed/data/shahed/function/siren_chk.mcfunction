# этот игрок слышал сирену меньше 13 с назад — вторую поверх не включаем
scoreboard players operation #dt shahed = #now shahed
scoreboard players operation #dt shahed -= @s shahed_sirt
execute if score #dt shahed matches 0..259 run return 0
scoreboard players operation @s shahed_sirt = #now shahed
tag @s add shahed_sir
