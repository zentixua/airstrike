scoreboard players remove @s shahed_fuse 1
execute at @s run function shahed:bunker/drill_visual
execute if score @s shahed_fuse matches ..0 run function shahed:bunker/detonate
