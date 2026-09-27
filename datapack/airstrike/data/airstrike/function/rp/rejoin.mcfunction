scoreboard players set @s airstrike_left 0
scoreboard players enable @s airstrike_rp
execute unless score @s airstrike_mode matches 2.. run function airstrike:rp/prompt
