execute if score #ci airstrike > #cn airstrike run return 0
scoreboard players operation #oyv airstrike = #ci airstrike
scoreboard players operation #oyv airstrike *= #-3 airstrike
execute store result storage airstrike:tmp br.oy int 1 run scoreboard players get #oyv airstrike
function airstrike:bfx/blob_rand with storage airstrike:tmp br
scoreboard players add #ci airstrike 1
function airstrike:bfx/chimney
