# «труба» обрушения из неровных комьев щебня от свода полости к поверхности
data modify storage airstrike:tmp br set value {rx:1,ry:1,rz:1,oy:0,rmin:1,rmax:2,blk:"minecraft:gravel",rep:"#airstrike:drillable"}
scoreboard players operation #cn airstrike = @s airstrike_E
scoreboard players remove #cn airstrike 8
scoreboard players operation #cn airstrike /= #3 airstrike
scoreboard players set #ci airstrike 1
function airstrike:bfx/chimney
# рваная воронка провала на поверхности
data modify storage airstrike:tmp br set value {rx:7,ry:1,rz:7,oy:-2,rmin:1,rmax:3,blk:"minecraft:gravel",rep:"#airstrike:bb_soil"}
scoreboard players set #bn airstrike 10
function airstrike:bfx/blobs
data modify storage airstrike:tmp br set value {rx:8,ry:1,rz:8,oy:-1,rmin:1,rmax:2,blk:"minecraft:coarse_dirt",rep:"#airstrike:bb_soil"}
scoreboard players set #bn airstrike 5
function airstrike:bfx/blobs
data modify storage airstrike:tmp br set value {rx:6,ry:1,rz:6,oy:-1,rmin:1,rmax:2,blk:"minecraft:rooted_dirt",rep:"#airstrike:bb_soil"}
scoreboard players set #bn airstrike 3
function airstrike:bfx/blobs
playsound minecraft:block.gravel.break block @a ~ ~ ~ 8 0.5
playsound minecraft:block.gravel.break block @a ~ ~ ~ 8 0.7
playsound airstrike:bb.quake master @a[distance=..120,scores={airstrike_mode=2..}] ~ ~ ~ 8 0.8 0.4
playsound minecraft:entity.warden.emerge master @a[distance=..120,scores={airstrike_mode=..1}] ~ ~ ~ 8 0.6 0.4
particle minecraft:campfire_cosy_smoke ~ ~1 ~ 4 1 4 0.02 60 force @a
execute as @a[distance=..50] unless score @s airstrike_quake matches 30.. run scoreboard players set @s airstrike_quake 30
