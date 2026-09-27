# /function airstrike:menu — пульт в чате
execute unless score @s airstrike_mt matches 1..3 run scoreboard players set @s airstrike_mt 1
execute unless score @s airstrike_mn matches 1.. run scoreboard players set @s airstrike_mn 3
execute unless score @s airstrike_mr matches 0.. run scoreboard players set @s airstrike_mr 25
function airstrike:menu/render
