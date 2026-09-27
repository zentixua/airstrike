scoreboard players add @s shahed_t 1
execute if score @s shahed_t matches 1 run function shahed:bfx/lights_on
execute if score @s shahed_t matches 1..30 run function shahed:bfx/front
execute if score @s shahed_t matches 1..8 run function shahed:bfx/cave_fire
execute if score @s shahed_t matches 2 run function shahed:bfx/rubble
execute if score @s shahed_t matches 3 run function shahed:bfx/secondary
execute if score @s shahed_t matches 8 run function shahed:bfx/lights_off
scoreboard players operation #m shahed = @s shahed_t
scoreboard players operation #m shahed %= #2 shahed
execute if score #m shahed matches 0 if score @s shahed_t matches 2..220 run function shahed:bfx/cave_smoke with entity @s data.sp
execute if score @s shahed_t matches 12 run playsound snassets:debris/debrissettle_stonesmall0 block @a[distance=..60] ~ ~ ~ 1 0.8 1
execute if score @s shahed_t matches 30 run playsound snassets:debris/debrissettle_stonesmall2 block @a[distance=..60] ~ ~ ~ 1 0.7 1
execute if score @s shahed_t matches 300.. run kill @s
