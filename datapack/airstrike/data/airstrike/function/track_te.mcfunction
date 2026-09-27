# цель — сущность (моб, поезд, механизм): одно чтение позиции за тик
$execute unless entity @e[tag=airstrike_te$(id),distance=..3000] run return 0
$data modify storage airstrike:tmp tp set from entity @e[tag=airstrike_te$(id),distance=..3000,limit=1] Pos
execute store result score #a airstrike run data get storage airstrike:tmp tp[0] 100
$scoreboard players set #b airstrike $(ox)
scoreboard players operation #a airstrike += #b airstrike
execute store result entity @s data.tx double 0.01 run scoreboard players get #a airstrike
execute store result score #a airstrike run data get storage airstrike:tmp tp[1] 100
$scoreboard players set #b airstrike $(oy)
scoreboard players operation #a airstrike += #b airstrike
execute store result entity @s data.ty double 0.01 run scoreboard players get #a airstrike
execute store result score #a airstrike run data get storage airstrike:tmp tp[2] 100
$scoreboard players set #b airstrike $(oz)
scoreboard players operation #a airstrike += #b airstrike
execute store result entity @s data.tz double 0.01 run scoreboard players get #a airstrike
function airstrike:track_scores
