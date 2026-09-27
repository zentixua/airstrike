function airstrike:fly/arc_cmd
scoreboard players set #W airstrike 400
scoreboard players set #A airstrike 25
scoreboard players add @s airstrike_v 4
execute if score @s airstrike_v matches 301.. run scoreboard players set @s airstrike_v 300
