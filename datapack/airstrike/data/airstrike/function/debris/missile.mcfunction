scoreboard players operation #mat airstrike = @s airstrike_mat
function airstrike:debris/ground
function airstrike:debris/ground
function airstrike:debris/ground
function airstrike:debris/ground
function airstrike:debris/char
function airstrike:debris/char
function airstrike:debris/wreck_missile
function airstrike:debris/burn
function airstrike:debris/burn
execute as @e[type=minecraft:falling_block,tag=airstrike_newdeb] run function airstrike:debris/fling_missile
tag @e[tag=airstrike_newdeb] remove airstrike_newdeb
