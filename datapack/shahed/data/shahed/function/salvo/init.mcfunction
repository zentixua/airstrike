tag @s remove shahed_newsalvo
scoreboard players operation @s shahed_id = #next shahed_id
scoreboard players operation @s shahed_ph = #st shahed
execute store result score @s shahed_E run data get storage shahed:tmp sv.count
scoreboard players operation @s shahed_trav = @s shahed_E
data modify entity @s data.r set from storage shahed:tmp sv.r
data modify entity @s data.yaw set from storage shahed:tmp sv.yaw
data modify entity @s data.who set from storage shahed:tmp sv.who
data modify entity @s data.sl set from storage shahed:tmp sv.sl
scoreboard players set @s shahed_t 1
