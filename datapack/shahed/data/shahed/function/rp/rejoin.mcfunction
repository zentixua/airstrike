scoreboard players set @s shahed_left 0
scoreboard players enable @s shahed_rp
execute unless score @s shahed_mode matches 2.. run function shahed:rp/prompt
