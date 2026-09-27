# плавный курс на цель (над самой целью курс не трогаем)
execute if score #hd2 shahed matches ..6400 run return 0
scoreboard players operation #ey shahed = #dyaw shahed
scoreboard players operation #ey shahed -= #cy shahed
execute if score #ey shahed matches 18001.. run scoreboard players remove #ey shahed 36000
execute if score #ey shahed matches ..-18001 run scoreboard players add #ey shahed 36000
execute if score #ey shahed matches 18001.. run scoreboard players remove #ey shahed 36000
execute if score #ey shahed matches ..-18001 run scoreboard players add #ey shahed 36000
scoreboard players operation #wc shahed = #ey shahed
scoreboard players operation #wc shahed *= #15 shahed
scoreboard players operation #wc shahed /= #100 shahed
scoreboard players operation #wc shahed < #300 shahed
scoreboard players operation #wc shahed > #-300 shahed
scoreboard players operation #wc shahed -= @s shahed_wy
scoreboard players operation #wc shahed < #30 shahed
scoreboard players operation #wc shahed > #-30 shahed
scoreboard players operation @s shahed_wy += #wc shahed
scoreboard players operation #cy shahed += @s shahed_wy
execute if score #cy shahed matches 18001.. run scoreboard players remove #cy shahed 36000
execute if score #cy shahed matches ..-18001 run scoreboard players add #cy shahed 36000
execute store result entity @s Rotation[0] float 0.01 run scoreboard players get #cy shahed
