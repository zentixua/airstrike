# что уцелело от ослабленной зоны — дроблёная порода; щебень под сводом осыпается в полость
fill ~-11 ~1 ~-11 ~11 ~11 ~11 minecraft:gravel replace minecraft:structure_void
fill ~-11 ~-11 ~-11 ~11 ~0 ~11 minecraft:cobblestone replace minecraft:structure_void
data modify storage airstrike:tmp br set value {rx:5,ry:1,rz:5,oy:-7,rmin:1,rmax:2,blk:"minecraft:magma_block",rep:"#airstrike:bb_rock"}
scoreboard players set #bn airstrike 3
function airstrike:bfx/blobs
