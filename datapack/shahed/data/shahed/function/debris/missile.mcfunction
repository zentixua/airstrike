scoreboard players operation #mat shahed = @s shahed_mat
function shahed:debris/ground
function shahed:debris/ground
function shahed:debris/ground
function shahed:debris/ground
function shahed:debris/char
function shahed:debris/char
function shahed:debris/wreck_missile
function shahed:debris/burn
function shahed:debris/burn
execute as @e[type=minecraft:falling_block,tag=shahed_newdeb] run function shahed:debris/fling_missile
tag @e[tag=shahed_newdeb] remove shahed_newdeb
