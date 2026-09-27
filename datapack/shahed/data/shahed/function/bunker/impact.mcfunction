tp @s ~ ~ ~
scoreboard players operation #cur_b shahed = @s shahed_id
scoreboard players set @s shahed_ph 5
execute store result score @s shahed_E run data get storage shahed:cfg bunker_energy
scoreboard players set @s shahed_trav 0
scoreboard players set @s shahed_fuse -1
# отметка входного отверстия: отсюда потом ударит «вулкан»
summon minecraft:marker ~ ~1 ~ {Tags:["shahed","shahed_vent","shahed_newvent"]}
execute as @e[type=minecraft:marker,tag=shahed_newvent] at @s run function shahed:bunker/vent_init
execute if data entity @s data{goal:1b} run function shahed:bunker/aim_goal with entity @s data
execute unless data entity @s data{goal:1b} run function shahed:bunker/aim_down
function shahed:bfx/impact
return 1
