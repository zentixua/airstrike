scoreboard players operation #mat shahed = @s shahed_mat
function shahed:debris/ground
function shahed:debris/ground
function shahed:debris/char
function shahed:debris/wreck_drone
function shahed:debris/burn
execute as @e[type=minecraft:falling_block,tag=shahed_newdeb] run function shahed:debris/fling_drone
tag @e[tag=shahed_newdeb] remove shahed_newdeb
