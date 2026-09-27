execute if score #ci shahed > #cn shahed run return 0
scoreboard players operation #oyv shahed = #ci shahed
scoreboard players operation #oyv shahed *= #-3 shahed
execute store result storage shahed:tmp br.oy int 1 run scoreboard players get #oyv shahed
function shahed:bfx/blob_rand with storage shahed:tmp br
scoreboard players add #ci shahed 1
function shahed:bfx/chimney
