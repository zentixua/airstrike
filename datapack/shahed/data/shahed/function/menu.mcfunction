# /function shahed:menu — пульт в чате
execute unless score @s shahed_mt matches 1..3 run scoreboard players set @s shahed_mt 1
execute unless score @s shahed_mn matches 1.. run scoreboard players set @s shahed_mn 3
execute unless score @s shahed_mr matches 0.. run scoreboard players set @s shahed_mr 25
function shahed:menu/render
