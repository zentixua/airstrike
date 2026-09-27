function shahed:fly/measure
# цель достигнута / таймаут
scoreboard players operation #reach shahed = @s shahed_v
scoreboard players operation #reach shahed /= #10 shahed
scoreboard players add #reach shahed 43
$execute if score #d shahed <= #reach shahed positioned $(tx) $(ty) $(tz) run return run function shahed:drone/detonate
execute if score @s shahed_t matches 900.. run return run function shahed:drone/detonate
$tp @e[type=minecraft:marker,tag=shahed_helper,sort=nearest,limit=1] ~ ~ ~ facing $(tx) $(ty) $(tz)
function shahed:fly/look
# крейсер → пикирование, когда цель ушла на 18° под горизонт
execute if score @s shahed_ph matches 0 if score #los shahed matches 1800.. run scoreboard players set @s shahed_ph 1
execute if score @s shahed_ph matches 0 run function shahed:drone/cruise_cmd
execute if score @s shahed_ph matches 1 run function shahed:drone/dive_cmd
function shahed:fly/pitch
function shahed:fly/yaw
scoreboard players set #nose shahed 365
data modify storage shahed:tmp mv.det set value "shahed:drone/detonate"
data modify storage shahed:tmp mv.vis set value "shahed:drone/visual"
execute at @s run return run function shahed:fly/move_prep
