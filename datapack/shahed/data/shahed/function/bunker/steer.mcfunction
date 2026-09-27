function shahed:fly/measure
scoreboard players operation #reach shahed = @s shahed_v
scoreboard players operation #reach shahed /= #10 shahed
scoreboard players add #reach shahed 53
$execute if score #d shahed <= #reach shahed positioned $(tx) $(ty) $(tz) run return run function shahed:bunker/impact
execute if score @s shahed_t matches 300.. run return run function shahed:bunker/impact
$tp @e[type=minecraft:marker,tag=shahed_helper,sort=nearest,limit=1] ~ ~ ~ facing $(tx) $(ty) $(tz)
function shahed:fly/look
function shahed:fly/arc_cmd
scoreboard players set #W shahed 700
scoreboard players set #A shahed 80
scoreboard players add @s shahed_v 30
execute if score @s shahed_v matches 1251.. run scoreboard players set @s shahed_v 1250
function shahed:fly/pitch
function shahed:fly/yaw
scoreboard players set #nose shahed 465
data modify storage shahed:tmp mv.det set value "shahed:bunker/impact"
data modify storage shahed:tmp mv.vis set value "shahed:bunker/visual"
execute at @s run return run function shahed:fly/move_prep
