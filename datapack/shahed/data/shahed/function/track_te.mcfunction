# цель — сущность (моб, поезд, механизм): одно чтение позиции за тик
$execute unless entity @e[tag=shahed_te$(id),distance=..3000] run return 0
$data modify storage shahed:tmp tp set from entity @e[tag=shahed_te$(id),distance=..3000,limit=1] Pos
execute store result score #a shahed run data get storage shahed:tmp tp[0] 100
$scoreboard players set #b shahed $(ox)
scoreboard players operation #a shahed += #b shahed
execute store result entity @s data.tx double 0.01 run scoreboard players get #a shahed
execute store result score #a shahed run data get storage shahed:tmp tp[1] 100
$scoreboard players set #b shahed $(oy)
scoreboard players operation #a shahed += #b shahed
execute store result entity @s data.ty double 0.01 run scoreboard players get #a shahed
execute store result score #a shahed run data get storage shahed:tmp tp[2] 100
$scoreboard players set #b shahed $(oz)
scoreboard players operation #a shahed += #b shahed
execute store result entity @s data.tz double 0.01 run scoreboard players get #a shahed
function shahed:track_scores
