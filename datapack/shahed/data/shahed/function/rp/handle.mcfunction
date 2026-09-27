execute if score @s shahed_rp matches 1 run function shahed:rp/on
execute if score @s shahed_rp matches 2 run function shahed:rp/test
execute if score @s shahed_rp matches 3 run function shahed:rp/off
scoreboard players set @s shahed_rp 0
scoreboard players enable @s shahed_rp
