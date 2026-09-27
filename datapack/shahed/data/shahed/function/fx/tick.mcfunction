scoreboard players add @s shahed_t 1
execute if score @s shahed_t matches 1 run function shahed:fx/lights_on
execute if score @s shahed_t matches 1..24 run function shahed:fx/front
scoreboard players operation #m3 shahed = @s shahed_t
scoreboard players operation #m3 shahed %= #3 shahed
execute if score @s shahed_t matches 1..18 if score #m3 shahed matches 0 run function shahed:snd/tail_drone
execute if score @s shahed_t matches 19 run tag @s remove shahed_snd
execute if score @s shahed_t matches 1..20 run function shahed:fx/ring_prep
execute if score @s shahed_t matches 1 run function shahed:fx/t1
execute if score @s shahed_t matches 3 run function shahed:fx/secondary_a
execute if score @s shahed_t matches 6 run function shahed:fx/secondary_b
execute if score @s shahed_t matches 5 run function shahed:fx/lights_off
execute if score @s shahed_t matches 1..14 run function shahed:fx/fireball_rise
scoreboard players operation #m shahed = @s shahed_t
scoreboard players operation #m shahed %= #2 shahed
execute if score #m shahed matches 0 if score @s shahed_t matches 2..220 run function shahed:fx/smoke
execute if score @s shahed_t matches 8 run playsound snassets:crashfire block @a ~ ~ ~ 3 1
execute if score @s shahed_t matches 140 run playsound snassets:crashfire block @a ~ ~ ~ 3 0.9
execute if score @s shahed_t matches 16 run playsound snassets:debris/debrissettle_dirtsmall0 block @a[distance=..60] ~ ~ ~ 0.9 1 0.9
execute if score @s shahed_t matches 26 run playsound snassets:debris/debrissettle_stonesmall1 block @a[distance=..60] ~ ~ ~ 0.8 1 0.8
execute if score @s shahed_t matches 220.. run kill @s
