scoreboard players set @s shahed_rel 1
data modify storage shahed:tmp b set from entity @s data
data modify storage shahed:tmp brot set from entity @s Rotation
execute positioned ~ ~-4 ~ run function shahed:bunker/bomb_spawn
