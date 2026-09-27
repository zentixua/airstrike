# разлёт до ~90–120 блоков: до 2.1 блока/тик (≈ 42 м/с) по горизонтали
execute store result entity @s Motion[0] double 0.01 run random value -210..210
execute store result entity @s Motion[1] double 0.01 run random value 60..220
execute store result entity @s Motion[2] double 0.01 run random value -210..210
execute if data storage shahed:cfg {debris_stay:0b} run data modify entity @s CancelDrop set value 1b
