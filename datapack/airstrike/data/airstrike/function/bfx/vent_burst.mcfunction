# газы взрыва вырываются по скважине: рёв, огонь и выброс грунта вверх
playsound airstrike:bb.vent master @a[distance=..160,scores={airstrike_mode=2..}] ~ ~ ~ 10 1 0.4
playsound minecraft:entity.blaze.shoot master @a[distance=..160,scores={airstrike_mode=..1}] ~ ~ ~ 8 0.5 0.4
playsound minecraft:block.fire.extinguish master @a[distance=..160,scores={airstrike_mode=..1}] ~ ~ ~ 8 0.5 0.4
scoreboard players operation #mat airstrike = @s airstrike_mat
function airstrike:debris/ground
function airstrike:debris/ground
function airstrike:debris/char
execute as @e[type=minecraft:falling_block,tag=airstrike_newdeb] run function airstrike:bunker/fling_vent
tag @e[tag=airstrike_newdeb] remove airstrike_newdeb
