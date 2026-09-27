scoreboard players remove @s airstrike_quake 1
execute if predicate airstrike:riding run return 0
scoreboard players operation #p airstrike = @s airstrike_quake
scoreboard players operation #p airstrike %= #2 airstrike
execute if score @s airstrike_quake matches 50.. if score #p airstrike matches 0 run tp @s ~ ~ ~ ~1.6 ~1.1
execute if score @s airstrike_quake matches 50.. if score #p airstrike matches 1 run tp @s ~ ~ ~ ~-1.6 ~-1.1
execute if score @s airstrike_quake matches 24..49 if score #p airstrike matches 0 run tp @s ~ ~ ~ ~0.9 ~0.6
execute if score @s airstrike_quake matches 24..49 if score #p airstrike matches 1 run tp @s ~ ~ ~ ~-0.9 ~-0.6
execute if score @s airstrike_quake matches ..23 if score #p airstrike matches 0 run tp @s ~ ~ ~ ~0.35 ~0.25
execute if score @s airstrike_quake matches ..23 if score #p airstrike matches 1 run tp @s ~ ~ ~ ~-0.35 ~-0.25
