# в породе боеприпас «гуляет»: случайные отклонения до 2.5°
execute store result score #cy shahed run data get entity @s Rotation[0] 100
execute store result score #cp shahed run data get entity @s Rotation[1] 100
execute store result score #j shahed run random value -250..250
scoreboard players operation #cy shahed += #j shahed
execute store result score #j shahed run random value -200..200
scoreboard players operation #cp shahed += #j shahed
execute if score #cp shahed matches ..4499 run scoreboard players set #cp shahed 4500
execute if score #cp shahed matches 8901.. run scoreboard players set #cp shahed 8900
execute store result entity @s Rotation[0] float 0.01 run scoreboard players get #cy shahed
execute store result entity @s Rotation[1] float 0.01 run scoreboard players get #cp shahed
