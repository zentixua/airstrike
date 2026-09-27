scoreboard players remove @s shahed_quake 1
execute if predicate shahed:riding run return 0
scoreboard players operation #p shahed = @s shahed_quake
scoreboard players operation #p shahed %= #2 shahed
execute if score @s shahed_quake matches 50.. if score #p shahed matches 0 run tp @s ~ ~ ~ ~1.6 ~1.1
execute if score @s shahed_quake matches 50.. if score #p shahed matches 1 run tp @s ~ ~ ~ ~-1.6 ~-1.1
execute if score @s shahed_quake matches 24..49 if score #p shahed matches 0 run tp @s ~ ~ ~ ~0.9 ~0.6
execute if score @s shahed_quake matches 24..49 if score #p shahed matches 1 run tp @s ~ ~ ~ ~-0.9 ~-0.6
execute if score @s shahed_quake matches ..23 if score #p shahed matches 0 run tp @s ~ ~ ~ ~0.35 ~0.25
execute if score @s shahed_quake matches ..23 if score #p shahed matches 1 run tp @s ~ ~ ~ ~-0.35 ~-0.25
