tp @s ~ ~ ~
scoreboard players operation #cur_b airstrike = @s airstrike_id
scoreboard players set @s airstrike_ph 5
execute store result score @s airstrike_E run data get storage airstrike:cfg bunker_energy
scoreboard players set @s airstrike_trav 0
scoreboard players set @s airstrike_fuse -1
# отметка входного отверстия: отсюда потом ударит «вулкан»
summon minecraft:marker ~ ~1 ~ {Tags:["airstrike","airstrike_vent","airstrike_newvent"]}
execute as @e[type=minecraft:marker,tag=airstrike_newvent] at @s run function airstrike:bunker/vent_init
execute if data entity @s data{goal:1b} run function airstrike:bunker/aim_goal with entity @s data
execute unless data entity @s data{goal:1b} run function airstrike:bunker/aim_down
function airstrike:bfx/impact
return 1
