# разлёт до ~55–75 блоков: до 1.5 блока/тик по горизонтали
execute store result entity @s Motion[0] double 0.01 run random value -150..150
execute store result entity @s Motion[1] double 0.01 run random value 50..160
execute store result entity @s Motion[2] double 0.01 run random value -150..150
execute if data storage airstrike:cfg {debris_stay:0b} run data modify entity @s CancelDrop set value 1b
