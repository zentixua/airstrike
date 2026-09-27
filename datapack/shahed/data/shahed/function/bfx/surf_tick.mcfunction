scoreboard players add @s shahed_t 1
execute if score @s shahed_t matches 1..16 if score @s shahed_E matches ..60 run function shahed:bfx/heave_prep
execute if score @s shahed_t matches 22 if data storage shahed:cfg {collapse:1b} if score @s shahed_E matches 4..48 run function shahed:bfx/collapse
scoreboard players operation #m shahed = @s shahed_t
scoreboard players operation #m shahed %= #2 shahed
execute if score #m shahed matches 0 if score @s shahed_t matches 24..200 if score @s shahed_E matches ..48 run function shahed:bfx/surf_smoke with entity @s data.sp
execute if score @s shahed_t matches 240.. run kill @s
