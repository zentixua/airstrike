# тряска камеры; игроков в сиденьях/транспорте не трогаем
scoreboard players remove @s shahed_shake 1
execute if predicate shahed:riding run return 0
scoreboard players operation #p shahed = @s shahed_shake
scoreboard players operation #p shahed %= #2 shahed
execute if score @s shahed_shake matches 26.. if score #p shahed matches 0 run tp @s ~ ~ ~ ~5 ~3.5
execute if score @s shahed_shake matches 26.. if score #p shahed matches 1 run tp @s ~ ~ ~ ~-5 ~-3.5
execute if score @s shahed_shake matches 18..25 if score #p shahed matches 0 run tp @s ~ ~ ~ ~3 ~2
execute if score @s shahed_shake matches 18..25 if score #p shahed matches 1 run tp @s ~ ~ ~ ~-3 ~-2
execute if score @s shahed_shake matches 8..17 if score #p shahed matches 0 run tp @s ~ ~ ~ ~1.5 ~1
execute if score @s shahed_shake matches 8..17 if score #p shahed matches 1 run tp @s ~ ~ ~ ~-1.5 ~-1
execute if score @s shahed_shake matches ..7 if score #p shahed matches 0 run tp @s ~ ~ ~ ~0.6 ~0.4
execute if score @s shahed_shake matches ..7 if score #p shahed matches 1 run tp @s ~ ~ ~ ~-0.6 ~-0.4
