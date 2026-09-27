tag @s remove shahed_new
data modify entity @s data set from storage shahed:tmp b
scoreboard players operation @s shahed_id = #next shahed_id
scoreboard players set @s shahed_ph 0
scoreboard players set @s shahed_t 0
scoreboard players set @s shahed_v 600
scoreboard players set @s shahed_wp 0
scoreboard players set @s shahed_wy 0
execute store result score @s shahed_tx run data get storage shahed:tmp b.tx 10
execute store result score @s shahed_ty run data get storage shahed:tmp b.ty 10
execute store result score @s shahed_tz run data get storage shahed:tmp b.tz 10
data modify entity @s Rotation set from storage shahed:tmp brot
data modify entity @s Rotation[1] set value 10.0f
execute at @s run function shahed:bunker/build_bomb
