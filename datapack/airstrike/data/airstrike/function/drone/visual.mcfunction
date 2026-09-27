scoreboard players operation #cur airstrike_id = @s airstrike_id
execute as @e[tag=airstrike_part,distance=..16] if score @s airstrike_id = #cur airstrike_id run tp @s ~ ~ ~ ~ ~
# выхлоп: сизый шлейф днём, тусклые искры выхлопа ночью
particle minecraft:campfire_cosy_smoke ^ ^0.05 ^-3.9 0.02 0.02 0.02 0.002 1 force @a
particle minecraft:small_flame ^ ^0.05 ^-3.8 0.02 0.02 0.02 0.002 1 force @a
particle minecraft:smoke ^ ^0.05 ^-3.8 0.05 0.05 0.05 0.01 2 force @a
execute if score @s airstrike_ph matches 1 run particle minecraft:smoke ^ ^0.05 ^-3.8 0.08 0.08 0.08 0.02 3 force @a
# звук мотора — раз в 3 тика каждому слушателю (направление, дальность, Доплер)
scoreboard players operation #m3 airstrike = @s airstrike_t
scoreboard players operation #m3 airstrike %= #3 airstrike
execute if score #m3 airstrike matches 0 run function airstrike:snd/source
