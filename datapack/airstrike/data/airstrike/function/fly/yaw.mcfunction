# плавный курс на цель (над самой целью курс не трогаем)
execute if score #hd2 airstrike matches ..6400 run return 0
scoreboard players operation #ey airstrike = #dyaw airstrike
scoreboard players operation #ey airstrike -= #cy airstrike
execute if score #ey airstrike matches 18001.. run scoreboard players remove #ey airstrike 36000
execute if score #ey airstrike matches ..-18001 run scoreboard players add #ey airstrike 36000
execute if score #ey airstrike matches 18001.. run scoreboard players remove #ey airstrike 36000
execute if score #ey airstrike matches ..-18001 run scoreboard players add #ey airstrike 36000
scoreboard players operation #wc airstrike = #ey airstrike
scoreboard players operation #wc airstrike *= #15 airstrike
scoreboard players operation #wc airstrike /= #100 airstrike
scoreboard players operation #wc airstrike < #300 airstrike
scoreboard players operation #wc airstrike > #-300 airstrike
scoreboard players operation #wc airstrike -= @s airstrike_wy
scoreboard players operation #wc airstrike < #30 airstrike
scoreboard players operation #wc airstrike > #-30 airstrike
scoreboard players operation @s airstrike_wy += #wc airstrike
scoreboard players operation #cy airstrike += @s airstrike_wy
execute if score #cy airstrike matches 18001.. run scoreboard players remove #cy airstrike 36000
execute if score #cy airstrike matches ..-18001 run scoreboard players add #cy airstrike 36000
execute store result entity @s Rotation[0] float 0.01 run scoreboard players get #cy airstrike
