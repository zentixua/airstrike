# источник звука — летящий шахед или ракета
execute store result score #sx airstrike run data get entity @s Pos[0] 10
execute store result score #sy airstrike run data get entity @s Pos[1] 10
execute store result score #sz airstrike run data get entity @s Pos[2] 10

scoreboard players operation #sid airstrike = @s airstrike_id
scoreboard players set #kind airstrike 1
execute if entity @s[tag=airstrike_missile] run scoreboard players set #kind airstrike 2
execute if entity @s[tag=airstrike_bomber] run scoreboard players set #kind airstrike 3
execute if entity @s[tag=airstrike_bunker] run scoreboard players set #kind airstrike 4
scoreboard players operation #sph airstrike = @s airstrike_ph
scoreboard players set #nodop airstrike 0
scoreboard players operation #wd airstrike = #d airstrike
scoreboard players operation #m6 airstrike = @s airstrike_t
scoreboard players operation #m6 airstrike %= #6 airstrike
scoreboard players operation #m12 airstrike = @s airstrike_t
scoreboard players operation #m12 airstrike %= #12 airstrike
execute as @a[distance=..320] at @s run function airstrike:snd/listener
