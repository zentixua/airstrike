function shahed:fly/measure
scoreboard players operation #reach shahed = @s shahed_v
scoreboard players operation #reach shahed /= #10 shahed
scoreboard players add #reach shahed 65
$execute if score #d shahed <= #reach shahed positioned $(tx) $(ty) $(tz) run return run function shahed:missile/detonate
execute if score @s shahed_t matches 700.. run return run function shahed:missile/detonate
$tp @e[type=minecraft:marker,tag=shahed_helper,sort=nearest,limit=1] ~ ~ ~ facing $(tx) $(ty) $(tz)
function shahed:fly/look
# 0 — бреющий полёт, 1 — горка с 160 блоков (до +32 над целью), 2 — пикирование по дуге
execute if score @s shahed_ph matches 0 if score #hd2 shahed matches ..2560000 if score @s shahed_pop matches 1 run scoreboard players set @s shahed_ph 1
execute if score @s shahed_ph matches 0 if score #hd2 shahed matches ..2560000 if score @s shahed_pop matches 0 run scoreboard players set @s shahed_ph 2
execute if score @s shahed_ph matches 1 if score #los shahed matches 2400.. run scoreboard players set @s shahed_ph 2
scoreboard players operation #t2 shahed = @s shahed_ty
scoreboard players operation #t2 shahed *= #10 shahed
scoreboard players add #t2 shahed 3200
execute if score @s shahed_ph matches 1 if score #ycb shahed >= #t2 shahed run scoreboard players set @s shahed_ph 2
execute if score @s shahed_ph matches 0 run function shahed:missile/cruise_cmd
execute if score @s shahed_ph matches 1 run function shahed:missile/pop_cmd
execute if score @s shahed_ph matches 2 run function shahed:missile/dive_cmd
function shahed:fly/pitch
function shahed:fly/yaw
scoreboard players set #nose shahed 588
data modify storage shahed:tmp mv.det set value "shahed:missile/detonate"
data modify storage shahed:tmp mv.vis set value "shahed:missile/visual"
execute at @s run return run function shahed:fly/move_prep
