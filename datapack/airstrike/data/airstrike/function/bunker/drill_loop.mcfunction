execute if score #n airstrike matches ..0 run return 0
execute if score @s airstrike_fuse matches 0.. run return 0
scoreboard players remove #n airstrike 1
execute at @s positioned ^ ^ ^1 run function airstrike:bunker/step
function airstrike:bunker/drill_loop
