scoreboard players add @s airstrike_t 1
execute if score @s airstrike_t matches 1 run function airstrike:fx/lights_on
execute if score @s airstrike_t matches 1..24 run function airstrike:fx/front
scoreboard players operation #m3 airstrike = @s airstrike_t
scoreboard players operation #m3 airstrike %= #3 airstrike
execute if score @s airstrike_t matches 1..18 if score #m3 airstrike matches 0 run function airstrike:snd/tail_drone
execute if score @s airstrike_t matches 19 run tag @s remove airstrike_snd
execute if score @s airstrike_t matches 1..20 run function airstrike:fx/ring_prep
execute if score @s airstrike_t matches 1 run function airstrike:fx/t1
execute if score @s airstrike_t matches 3 run function airstrike:fx/secondary_a
execute if score @s airstrike_t matches 6 run function airstrike:fx/secondary_b
execute if score @s airstrike_t matches 5 run function airstrike:fx/lights_off
execute if score @s airstrike_t matches 1..14 run function airstrike:fx/fireball_rise
scoreboard players operation #m airstrike = @s airstrike_t
scoreboard players operation #m airstrike %= #2 airstrike
execute if score #m airstrike matches 0 if score @s airstrike_t matches 2..220 run function airstrike:fx/smoke
execute if score @s airstrike_t matches 8 run playsound snassets:crashfire block @a ~ ~ ~ 3 1
execute if score @s airstrike_t matches 140 run playsound snassets:crashfire block @a ~ ~ ~ 3 0.9
execute if score @s airstrike_t matches 16 run playsound snassets:debris/debrissettle_dirtsmall0 block @a[distance=..60] ~ ~ ~ 0.9 1 0.9
execute if score @s airstrike_t matches 26 run playsound snassets:debris/debrissettle_stonesmall1 block @a[distance=..60] ~ ~ ~ 0.8 1 0.8
execute if score @s airstrike_t matches 220.. run kill @s
