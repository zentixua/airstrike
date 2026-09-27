tag @s remove airstrike_new
data modify entity @s data set from storage airstrike:tmp d
scoreboard players operation @s airstrike_id = #next airstrike_id
scoreboard players set @s airstrike_t 0
scoreboard players set @s airstrike_rel 0
scoreboard players set @s airstrike_ph 0
execute store result score @s airstrike_tx run data get storage airstrike:tmp d.tx 10
execute store result score @s airstrike_ty run data get storage airstrike:tmp d.ty 10
execute store result score @s airstrike_tz run data get storage airstrike:tmp d.tz 10
# эшелон: 170 блоков над целью
execute store result score #y airstrike run data get storage airstrike:tmp d.ty 100
scoreboard players add #y airstrike 17000
execute store result entity @s Pos[1] double 0.01 run scoreboard players get #y airstrike
execute at @s run function airstrike:drone/face with entity @s data
execute at @s run function airstrike:bunker/build_bomber
