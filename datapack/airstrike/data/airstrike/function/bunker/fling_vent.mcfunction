execute store result entity @s Motion[0] double 0.01 run random value -45..45
execute store result entity @s Motion[1] double 0.01 run random value 120..270
execute store result entity @s Motion[2] double 0.01 run random value -45..45
execute if data storage airstrike:cfg {debris_stay:0b} run data modify entity @s CancelDrop set value 1b
