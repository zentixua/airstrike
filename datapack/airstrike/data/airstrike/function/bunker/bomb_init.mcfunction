tag @s remove airstrike_new
data modify entity @s data set from storage airstrike:tmp b
scoreboard players operation @s airstrike_id = #next airstrike_id
scoreboard players set @s airstrike_ph 0
scoreboard players set @s airstrike_t 0
scoreboard players set @s airstrike_v 600
scoreboard players set @s airstrike_wp 0
scoreboard players set @s airstrike_wy 0
execute store result score @s airstrike_tx run data get storage airstrike:tmp b.tx 10
execute store result score @s airstrike_ty run data get storage airstrike:tmp b.ty 10
execute store result score @s airstrike_tz run data get storage airstrike:tmp b.tz 10
data modify entity @s Rotation set from storage airstrike:tmp brot
data modify entity @s Rotation[1] set value 10.0f
execute at @s run function airstrike:bunker/build_bomb
