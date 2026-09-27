scoreboard players add @s shahed_t 1
execute if score @s shahed_t matches 1 run function shahed:mfx/lights_on
execute if score @s shahed_t matches 1..30 run function shahed:mfx/front
scoreboard players operation #m3 shahed = @s shahed_t
scoreboard players operation #m3 shahed %= #3 shahed
execute if score @s shahed_t matches 1..18 if score #m3 shahed matches 0 run function shahed:snd/tail_missile
execute if score @s shahed_t matches 19 run tag @s remove shahed_snd
execute if score @s shahed_t matches 1..30 run function shahed:mfx/ring_prep
execute if score @s shahed_t matches 1..10 run function shahed:mfx/sphere_prep
execute if score @s shahed_t matches 1 run function shahed:mfx/t1
execute if score @s shahed_t matches 3 run function shahed:mfx/t3
execute if score @s shahed_t matches 5 run function shahed:mfx/t5
execute if score @s shahed_t matches 9 run function shahed:mfx/t9
execute if score @s shahed_t matches 1..140 run function shahed:mfx/fireball_rise
scoreboard players operation #m shahed = @s shahed_t
scoreboard players operation #m shahed %= #2 shahed
execute if score #m shahed matches 0 if score @s shahed_t matches 2..320 run function shahed:mfx/smoke
execute if score @s shahed_t matches 10 run playsound snassets:crashfire block @a ~ ~ ~ 4 0.9
execute if score @s shahed_t matches 150 run playsound snassets:totaledfire block @a ~ ~ ~ 4 0.9
execute if score @s shahed_t matches 18 run playsound snassets:debris/debrissettle_dirtsmall1 block @a[distance=..90] ~ ~ ~ 1 0.9 1
execute if score @s shahed_t matches 26 run playsound snassets:debris/debrissettle_woodlarge1 block @a[distance=..90] ~ ~ ~ 0.9 0.9 0.9
execute if score @s shahed_t matches 34 run playsound snassets:debris/debrissettle_stonesmall0 block @a[distance=..90] ~ ~ ~ 0.9 0.9 0.9
execute if score @s shahed_t matches 60 run function shahed:mfx/cookoff
execute if score @s shahed_t matches 97 run function shahed:mfx/cookoff
execute if score @s shahed_t matches 143 run function shahed:mfx/cookoff
execute if score @s shahed_t matches 320.. run kill @s
