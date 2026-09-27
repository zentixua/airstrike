scoreboard players operation #cur airstrike_id = @s airstrike_id
execute as @e[tag=airstrike_part,distance=..24] if score @s airstrike_id = #cur airstrike_id run tp @s ~ ~ ~ ~ ~
execute as @e[type=minecraft:marker,tag=airstrike_vent] if score @s airstrike_id = #cur airstrike_id at @s run particle minecraft:campfire_cosy_smoke ~ ~0.3 ~ 0.3 0.2 0.3 0.02 3 force @a
scoreboard players operation #m3 airstrike = @s airstrike_t
scoreboard players operation #m3 airstrike %= #3 airstrike
execute if score #m3 airstrike matches 0 run playsound airstrike:bb.drill ambient @a[distance=..96,scores={airstrike_mode=2..}] ~ ~ ~ 6 0.85 0
scoreboard players operation #m12 airstrike = @s airstrike_t
scoreboard players operation #m12 airstrike %= #12 airstrike
execute if score #m12 airstrike matches 0 run playsound minecraft:entity.warden.dig ambient @a[distance=..96,scores={airstrike_mode=..1}] ~ ~ ~ 6 0.7 0
execute as @a[distance=..40] unless score @s airstrike_quake matches 10.. run scoreboard players set @s airstrike_quake 10
