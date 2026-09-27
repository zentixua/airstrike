scoreboard players add @s airstrike_t 1
execute if score @s airstrike_t matches 1 run function airstrike:mfx/lights_on
execute if score @s airstrike_t matches 1..30 run function airstrike:mfx/front
scoreboard players operation #m3 airstrike = @s airstrike_t
scoreboard players operation #m3 airstrike %= #3 airstrike
execute if score @s airstrike_t matches 1..18 if score #m3 airstrike matches 0 run function airstrike:snd/tail_missile
execute if score @s airstrike_t matches 19 run tag @s remove airstrike_snd
execute if score @s airstrike_t matches 1..30 run function airstrike:mfx/ring_prep
execute if score @s airstrike_t matches 1..10 run function airstrike:mfx/sphere_prep
execute if score @s airstrike_t matches 1 run function airstrike:mfx/t1
execute if score @s airstrike_t matches 3 run function airstrike:mfx/t3
execute if score @s airstrike_t matches 5 run function airstrike:mfx/t5
execute if score @s airstrike_t matches 9 run function airstrike:mfx/t9
execute if score @s airstrike_t matches 1..140 run function airstrike:mfx/fireball_rise
scoreboard players operation #m airstrike = @s airstrike_t
scoreboard players operation #m airstrike %= #2 airstrike
execute if score #m airstrike matches 0 if score @s airstrike_t matches 2..320 run function airstrike:mfx/smoke
execute if score @s airstrike_t matches 10 run playsound snassets:crashfire block @a ~ ~ ~ 4 0.9
execute if score @s airstrike_t matches 150 run playsound snassets:totaledfire block @a ~ ~ ~ 4 0.9
execute if score @s airstrike_t matches 18 run playsound snassets:debris/debrissettle_dirtsmall1 block @a[distance=..90] ~ ~ ~ 1 0.9 1
execute if score @s airstrike_t matches 26 run playsound snassets:debris/debrissettle_woodlarge1 block @a[distance=..90] ~ ~ ~ 0.9 0.9 0.9
execute if score @s airstrike_t matches 34 run playsound snassets:debris/debrissettle_stonesmall0 block @a[distance=..90] ~ ~ ~ 0.9 0.9 0.9
execute if score @s airstrike_t matches 60 run function airstrike:mfx/cookoff
execute if score @s airstrike_t matches 97 run function airstrike:mfx/cookoff
execute if score @s airstrike_t matches 143 run function airstrike:mfx/cookoff
execute if score @s airstrike_t matches 320.. run kill @s
