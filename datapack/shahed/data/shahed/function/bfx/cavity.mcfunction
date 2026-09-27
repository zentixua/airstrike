# порода вокруг заряда в неровных комьях заменяется льдом: он не даёт дропа и почти не держит взрыв,
# поэтому взрыв сам выгрызает полость рваной, «природной» формы
data modify storage shahed:tmp br set value {rx:6,ry:5,rz:6,oy:0,rmin:3,rmax:5,blk:"minecraft:structure_void",rep:"#shahed:drillable"}
scoreboard players set #bn shahed 12
function shahed:bfx/blobs
execute store result storage shahed:tmp ck.x int 1 run random value -3..3
execute store result storage shahed:tmp ck.y int 1 run random value -2..2
execute store result storage shahed:tmp ck.z int 1 run random value -3..3
function shahed:bfx/extra_blast with storage shahed:tmp ck
execute store result storage shahed:tmp ck.x int 1 run random value -3..3
execute store result storage shahed:tmp ck.y int 1 run random value -2..2
execute store result storage shahed:tmp ck.z int 1 run random value -3..3
function shahed:bfx/extra_blast with storage shahed:tmp ck
summon minecraft:small_fireball ~5 ~5 ~0 {Motion:[0.0d,-1.2d,0.0d],Tags:["shahed"]}
summon minecraft:small_fireball ~2.5 ~5 ~4.3 {Motion:[0.0d,-1.2d,0.0d],Tags:["shahed"]}
summon minecraft:small_fireball ~-2.5 ~5 ~4.3 {Motion:[0.0d,-1.2d,0.0d],Tags:["shahed"]}
summon minecraft:small_fireball ~-5 ~5 ~0 {Motion:[0.0d,-1.2d,0.0d],Tags:["shahed"]}
summon minecraft:small_fireball ~-2.5 ~5 ~-4.3 {Motion:[0.0d,-1.2d,0.0d],Tags:["shahed"]}
summon minecraft:small_fireball ~2.5 ~5 ~-4.3 {Motion:[0.0d,-1.2d,0.0d],Tags:["shahed"]}
