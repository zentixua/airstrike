function airstrike:fly/measure
scoreboard players operation #reach airstrike = @s airstrike_v
scoreboard players operation #reach airstrike /= #10 airstrike
scoreboard players add #reach airstrike 53
$execute if score #d airstrike <= #reach airstrike positioned $(tx) $(ty) $(tz) run return run function airstrike:bunker/impact
execute if score @s airstrike_t matches 300.. run return run function airstrike:bunker/impact
$tp @e[type=minecraft:marker,tag=airstrike_helper,sort=nearest,limit=1] ~ ~ ~ facing $(tx) $(ty) $(tz)
function airstrike:fly/look
function airstrike:fly/arc_cmd
scoreboard players set #W airstrike 700
scoreboard players set #A airstrike 80
scoreboard players add @s airstrike_v 30
execute if score @s airstrike_v matches 1251.. run scoreboard players set @s airstrike_v 1250
function airstrike:fly/pitch
function airstrike:fly/yaw
scoreboard players set #nose airstrike 465
data modify storage airstrike:tmp mv.det set value "airstrike:bunker/impact"
data modify storage airstrike:tmp mv.vis set value "airstrike:bunker/visual"
execute at @s run return run function airstrike:fly/move_prep
