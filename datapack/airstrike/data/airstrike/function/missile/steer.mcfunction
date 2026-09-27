function airstrike:fly/measure
scoreboard players operation #reach airstrike = @s airstrike_v
scoreboard players operation #reach airstrike /= #10 airstrike
scoreboard players add #reach airstrike 65
$execute if score #d airstrike <= #reach airstrike positioned $(tx) $(ty) $(tz) run return run function airstrike:missile/detonate
execute if score @s airstrike_t matches 700.. run return run function airstrike:missile/detonate
$tp @e[type=minecraft:marker,tag=airstrike_helper,sort=nearest,limit=1] ~ ~ ~ facing $(tx) $(ty) $(tz)
function airstrike:fly/look
# 0 — бреющий полёт, 1 — горка с 160 блоков (до +32 над целью), 2 — пикирование по дуге
execute if score @s airstrike_ph matches 0 if score #hd2 airstrike matches ..2560000 if score @s airstrike_pop matches 1 run scoreboard players set @s airstrike_ph 1
execute if score @s airstrike_ph matches 0 if score #hd2 airstrike matches ..2560000 if score @s airstrike_pop matches 0 run scoreboard players set @s airstrike_ph 2
execute if score @s airstrike_ph matches 1 if score #los airstrike matches 2400.. run scoreboard players set @s airstrike_ph 2
scoreboard players operation #t2 airstrike = @s airstrike_ty
scoreboard players operation #t2 airstrike *= #10 airstrike
scoreboard players add #t2 airstrike 3200
execute if score @s airstrike_ph matches 1 if score #ycb airstrike >= #t2 airstrike run scoreboard players set @s airstrike_ph 2
execute if score @s airstrike_ph matches 0 run function airstrike:missile/cruise_cmd
execute if score @s airstrike_ph matches 1 run function airstrike:missile/pop_cmd
execute if score @s airstrike_ph matches 2 run function airstrike:missile/dive_cmd
function airstrike:fly/pitch
function airstrike:fly/yaw
scoreboard players set #nose airstrike 588
data modify storage airstrike:tmp mv.det set value "airstrike:missile/detonate"
data modify storage airstrike:tmp mv.vis set value "airstrike:missile/visual"
execute at @s run return run function airstrike:fly/move_prep
