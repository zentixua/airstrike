scoreboard players set @s airstrike_rel 1
data modify storage airstrike:tmp b set from entity @s data
data modify storage airstrike:tmp brot set from entity @s Rotation
execute positioned ~ ~-4 ~ run function airstrike:bunker/bomb_spawn
