scoreboard players operation #cur shahed_id = @s shahed_id
execute as @e[tag=shahed_part,distance=..24] if score @s shahed_id = #cur shahed_id run tp @s ~ ~ ~ ~ ~
execute as @e[type=minecraft:marker,tag=shahed_vent] if score @s shahed_id = #cur shahed_id at @s run particle minecraft:campfire_cosy_smoke ~ ~0.3 ~ 0.3 0.2 0.3 0.02 3 force @a
scoreboard players operation #m3 shahed = @s shahed_t
scoreboard players operation #m3 shahed %= #3 shahed
execute if score #m3 shahed matches 0 run playsound shahed:bb.drill ambient @a[distance=..96,scores={shahed_mode=2..}] ~ ~ ~ 6 0.85 0
scoreboard players operation #m12 shahed = @s shahed_t
scoreboard players operation #m12 shahed %= #12 shahed
execute if score #m12 shahed matches 0 run playsound minecraft:entity.warden.dig ambient @a[distance=..96,scores={shahed_mode=..1}] ~ ~ ~ 6 0.7 0
execute as @a[distance=..40] unless score @s shahed_quake matches 10.. run scoreboard players set @s shahed_quake 10
