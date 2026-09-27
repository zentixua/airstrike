# газы взрыва вырываются по скважине: рёв, огонь и выброс грунта вверх
playsound shahed:bb.vent master @a[distance=..160,scores={shahed_mode=2..}] ~ ~ ~ 10 1 0.4
playsound minecraft:entity.blaze.shoot master @a[distance=..160,scores={shahed_mode=..1}] ~ ~ ~ 8 0.5 0.4
playsound minecraft:block.fire.extinguish master @a[distance=..160,scores={shahed_mode=..1}] ~ ~ ~ 8 0.5 0.4
scoreboard players operation #mat shahed = @s shahed_mat
function shahed:debris/ground
function shahed:debris/ground
function shahed:debris/char
execute as @e[type=minecraft:falling_block,tag=shahed_newdeb] run function shahed:bunker/fling_vent
tag @e[tag=shahed_newdeb] remove shahed_newdeb
