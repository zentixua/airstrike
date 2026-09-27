execute if score #n shahed matches ..0 run return 0
execute if score @s shahed_fuse matches 0.. run return 0
scoreboard players remove #n shahed 1
execute at @s positioned ^ ^ ^1 run function shahed:bunker/step
function shahed:bunker/drill_loop
