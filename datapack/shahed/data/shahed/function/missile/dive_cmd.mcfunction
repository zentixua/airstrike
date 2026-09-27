function shahed:fly/arc_cmd
scoreboard players set #W shahed 1600
scoreboard players set #A shahed 350
scoreboard players add @s shahed_v 10
execute if score @s shahed_v matches 1251.. run scoreboard players set @s shahed_v 1250
