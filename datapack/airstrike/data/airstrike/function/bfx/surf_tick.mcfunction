scoreboard players add @s airstrike_t 1
execute if score @s airstrike_t matches 1..16 if score @s airstrike_E matches ..60 run function airstrike:bfx/heave_prep
execute if score @s airstrike_t matches 22 if data storage airstrike:cfg {collapse:1b} if score @s airstrike_E matches 4..48 run function airstrike:bfx/collapse
scoreboard players operation #m airstrike = @s airstrike_t
scoreboard players operation #m airstrike %= #2 airstrike
execute if score #m airstrike matches 0 if score @s airstrike_t matches 24..200 if score @s airstrike_E matches ..48 run function airstrike:bfx/surf_smoke with entity @s data.sp
execute if score @s airstrike_t matches 240.. run kill @s
