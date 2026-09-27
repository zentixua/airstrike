tag @s remove shahed_new
data modify entity @s data set from storage shahed:tmp d
scoreboard players operation @s shahed_id = #next shahed_id
scoreboard players set @s shahed_t 0
scoreboard players set @s shahed_rel 0
scoreboard players set @s shahed_ph 0
execute store result score @s shahed_tx run data get storage shahed:tmp d.tx 10
execute store result score @s shahed_ty run data get storage shahed:tmp d.ty 10
execute store result score @s shahed_tz run data get storage shahed:tmp d.tz 10
# эшелон: 170 блоков над целью
execute store result score #y shahed run data get storage shahed:tmp d.ty 100
scoreboard players add #y shahed 17000
execute store result entity @s Pos[1] double 0.01 run scoreboard players get #y shahed
execute at @s run function shahed:drone/face with entity @s data
execute at @s run function shahed:bunker/build_bomber
