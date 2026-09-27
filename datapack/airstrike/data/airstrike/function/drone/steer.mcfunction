function airstrike:fly/measure
# цель достигнута / таймаут
scoreboard players operation #reach airstrike = @s airstrike_v
scoreboard players operation #reach airstrike /= #10 airstrike
scoreboard players add #reach airstrike 43
$execute if score #d airstrike <= #reach airstrike positioned $(tx) $(ty) $(tz) run return run function airstrike:drone/detonate
execute if score @s airstrike_t matches 900.. run return run function airstrike:drone/detonate
$tp @e[type=minecraft:marker,tag=airstrike_helper,sort=nearest,limit=1] ~ ~ ~ facing $(tx) $(ty) $(tz)
function airstrike:fly/look
# крейсер → пикирование, когда цель ушла на 18° под горизонт
execute if score @s airstrike_ph matches 0 if score #los airstrike matches 1800.. run scoreboard players set @s airstrike_ph 1
execute if score @s airstrike_ph matches 0 run function airstrike:drone/cruise_cmd
execute if score @s airstrike_ph matches 1 run function airstrike:drone/dive_cmd
function airstrike:fly/pitch
function airstrike:fly/yaw
scoreboard players set #nose airstrike 365
data modify storage airstrike:tmp mv.det set value "airstrike:drone/detonate"
data modify storage airstrike:tmp mv.vis set value "airstrike:drone/visual"
execute at @s run return run function airstrike:fly/move_prep
