function airstrike:fly/arc_cmd
scoreboard players set #W airstrike 1600
scoreboard players set #A airstrike 350
scoreboard players add @s airstrike_v 10
execute if score @s airstrike_v matches 1251.. run scoreboard players set @s airstrike_v 1250
