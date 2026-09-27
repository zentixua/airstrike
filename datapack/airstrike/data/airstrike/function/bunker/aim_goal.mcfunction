$tp @s ~ ~ ~ facing $(gx) $(gy) $(gz)
execute store result score #cp airstrike run data get entity @s Rotation[1] 100
execute if score #cp airstrike matches ..4499 run data modify entity @s Rotation[1] set value 45.0f
