# в породе боеприпас «гуляет»: случайные отклонения до 2.5°
execute store result score #cy airstrike run data get entity @s Rotation[0] 100
execute store result score #cp airstrike run data get entity @s Rotation[1] 100
execute store result score #j airstrike run random value -250..250
scoreboard players operation #cy airstrike += #j airstrike
execute store result score #j airstrike run random value -200..200
scoreboard players operation #cp airstrike += #j airstrike
execute if score #cp airstrike matches ..4499 run scoreboard players set #cp airstrike 4500
execute if score #cp airstrike matches 8901.. run scoreboard players set #cp airstrike 8900
execute store result entity @s Rotation[0] float 0.01 run scoreboard players get #cy airstrike
execute store result entity @s Rotation[1] float 0.01 run scoreboard players get #cp airstrike
