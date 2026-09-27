scoreboard players operation #cur shahed_id = @s shahed_id
execute as @e[tag=shahed_part,distance=..16] if score @s shahed_id = #cur shahed_id run tp @s ~ ~ ~ ~ ~
# выхлоп: сизый шлейф днём, тусклые искры выхлопа ночью
particle minecraft:campfire_cosy_smoke ^ ^0.05 ^-3.9 0.02 0.02 0.02 0.002 1 force @a
particle minecraft:small_flame ^ ^0.05 ^-3.8 0.02 0.02 0.02 0.002 1 force @a
particle minecraft:smoke ^ ^0.05 ^-3.8 0.05 0.05 0.05 0.01 2 force @a
execute if score @s shahed_ph matches 1 run particle minecraft:smoke ^ ^0.05 ^-3.8 0.08 0.08 0.08 0.02 3 force @a
# звук мотора — раз в 3 тика каждому слушателю (направление, дальность, Доплер)
scoreboard players operation #m3 shahed = @s shahed_t
scoreboard players operation #m3 shahed %= #3 shahed
execute if score #m3 shahed matches 0 run function shahed:snd/source
