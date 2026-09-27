# что уцелело от ослабленной зоны — дроблёная порода; щебень под сводом осыпается в полость
fill ~-11 ~1 ~-11 ~11 ~11 ~11 minecraft:gravel replace minecraft:structure_void
fill ~-11 ~-11 ~-11 ~11 ~0 ~11 minecraft:cobblestone replace minecraft:structure_void
data modify storage shahed:tmp br set value {rx:5,ry:1,rz:5,oy:-7,rmin:1,rmax:2,blk:"minecraft:magma_block",rep:"#shahed:bb_rock"}
scoreboard players set #bn shahed 3
function shahed:bfx/blobs
