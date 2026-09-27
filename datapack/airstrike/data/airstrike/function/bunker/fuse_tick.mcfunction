scoreboard players remove @s airstrike_fuse 1
execute at @s run function airstrike:bunker/drill_visual
execute if score @s airstrike_fuse matches ..0 run function airstrike:bunker/detonate
