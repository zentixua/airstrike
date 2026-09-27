execute if score @s airstrike_rp matches 1 run function airstrike:rp/on
execute if score @s airstrike_rp matches 2 run function airstrike:rp/test
execute if score @s airstrike_rp matches 3 run function airstrike:rp/off
scoreboard players set @s airstrike_rp 0
scoreboard players enable @s airstrike_rp
