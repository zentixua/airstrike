# источник звука — летящий шахед или ракета
execute store result score #sx shahed run data get entity @s Pos[0] 10
execute store result score #sy shahed run data get entity @s Pos[1] 10
execute store result score #sz shahed run data get entity @s Pos[2] 10

scoreboard players operation #sid shahed = @s shahed_id
scoreboard players set #kind shahed 1
execute if entity @s[tag=shahed_missile] run scoreboard players set #kind shahed 2
execute if entity @s[tag=shahed_bomber] run scoreboard players set #kind shahed 3
execute if entity @s[tag=shahed_bunker] run scoreboard players set #kind shahed 4
scoreboard players operation #sph shahed = @s shahed_ph
scoreboard players set #nodop shahed 0
scoreboard players operation #wd shahed = #d shahed
scoreboard players operation #m6 shahed = @s shahed_t
scoreboard players operation #m6 shahed %= #6 shahed
scoreboard players operation #m12 shahed = @s shahed_t
scoreboard players operation #m12 shahed %= #12 shahed
execute as @a[distance=..320] at @s run function shahed:snd/listener
