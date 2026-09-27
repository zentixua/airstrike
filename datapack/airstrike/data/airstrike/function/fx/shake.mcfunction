# тряска камеры; игроков в сиденьях/транспорте не трогаем
scoreboard players remove @s airstrike_shake 1
execute if predicate airstrike:riding run return 0
scoreboard players operation #p airstrike = @s airstrike_shake
scoreboard players operation #p airstrike %= #2 airstrike
execute if score @s airstrike_shake matches 26.. if score #p airstrike matches 0 run tp @s ~ ~ ~ ~5 ~3.5
execute if score @s airstrike_shake matches 26.. if score #p airstrike matches 1 run tp @s ~ ~ ~ ~-5 ~-3.5
execute if score @s airstrike_shake matches 18..25 if score #p airstrike matches 0 run tp @s ~ ~ ~ ~3 ~2
execute if score @s airstrike_shake matches 18..25 if score #p airstrike matches 1 run tp @s ~ ~ ~ ~-3 ~-2
execute if score @s airstrike_shake matches 8..17 if score #p airstrike matches 0 run tp @s ~ ~ ~ ~1.5 ~1
execute if score @s airstrike_shake matches 8..17 if score #p airstrike matches 1 run tp @s ~ ~ ~ ~-1.5 ~-1
execute if score @s airstrike_shake matches ..7 if score #p airstrike matches 0 run tp @s ~ ~ ~ ~0.6 ~0.4
execute if score @s airstrike_shake matches ..7 if score #p airstrike matches 1 run tp @s ~ ~ ~ ~-0.6 ~-0.4
