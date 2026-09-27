tag @s remove airstrike_newsalvo
scoreboard players operation @s airstrike_id = #next airstrike_id
scoreboard players operation @s airstrike_ph = #st airstrike
execute store result score @s airstrike_E run data get storage airstrike:tmp sv.count
scoreboard players operation @s airstrike_trav = @s airstrike_E
data modify entity @s data.r set from storage airstrike:tmp sv.r
data modify entity @s data.yaw set from storage airstrike:tmp sv.yaw
data modify entity @s data.who set from storage airstrike:tmp sv.who
data modify entity @s data.sl set from storage airstrike:tmp sv.sl
scoreboard players set @s airstrike_t 1
