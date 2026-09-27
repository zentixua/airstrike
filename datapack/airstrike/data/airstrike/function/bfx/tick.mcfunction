scoreboard players add @s airstrike_t 1
execute if score @s airstrike_t matches 1 run function airstrike:bfx/lights_on
execute if score @s airstrike_t matches 1..30 run function airstrike:bfx/front
execute if score @s airstrike_t matches 1..8 run function airstrike:bfx/cave_fire
execute if score @s airstrike_t matches 2 run function airstrike:bfx/rubble
execute if score @s airstrike_t matches 3 run function airstrike:bfx/secondary
execute if score @s airstrike_t matches 8 run function airstrike:bfx/lights_off
scoreboard players operation #m airstrike = @s airstrike_t
scoreboard players operation #m airstrike %= #2 airstrike
execute if score #m airstrike matches 0 if score @s airstrike_t matches 2..220 run function airstrike:bfx/cave_smoke with entity @s data.sp
execute if score @s airstrike_t matches 12 run playsound snassets:debris/debrissettle_stonesmall0 block @a[distance=..60] ~ ~ ~ 1 0.8 1
execute if score @s airstrike_t matches 30 run playsound snassets:debris/debrissettle_stonesmall2 block @a[distance=..60] ~ ~ ~ 1 0.7 1
execute if score @s airstrike_t matches 300.. run kill @s
