scoreboard players set @s shahed_seen 1
execute unless score @s shahed_mode matches 0.. run scoreboard players set @s shahed_mode 0
scoreboard players enable @s shahed_rp
function shahed:rp/prompt
