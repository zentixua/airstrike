function shahed:fly/arc_cmd
scoreboard players set #W shahed 400
scoreboard players set #A shahed 25
scoreboard players add @s shahed_v 4
execute if score @s shahed_v matches 301.. run scoreboard players set @s shahed_v 300
