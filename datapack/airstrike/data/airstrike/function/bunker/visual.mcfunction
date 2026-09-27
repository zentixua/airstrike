scoreboard players operation #cur airstrike_id = @s airstrike_id
execute as @e[tag=airstrike_part,distance=..24] if score @s airstrike_id = #cur airstrike_id run tp @s ~ ~ ~ ~ ~
particle minecraft:cloud ^ ^ ^-5.2 0.08 0.08 0.08 0.004 3 force @a
particle minecraft:campfire_cosy_smoke ^ ^ ^-5.5 0.05 0.05 0.05 0.002 1 force @a
# у звукового барьера — конденсационный «воротник» у головы
execute if score @s airstrike_v matches 800.. run particle minecraft:white_smoke ^ ^ ^2.5 0.6 0.6 0.6 0.02 8 force @a
scoreboard players operation #m3 airstrike = @s airstrike_t
scoreboard players operation #m3 airstrike %= #3 airstrike
execute if score #m3 airstrike matches 0 run function airstrike:snd/source
