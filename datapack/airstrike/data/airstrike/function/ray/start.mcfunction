# луч от глаз: аппарат Create → сущность → блок; что встретится первым, то и цель
kill @e[type=minecraft:marker,tag=airstrike_tgt_new]
data remove storage airstrike:tmp sl
tag @s add airstrike_shooter
execute on vehicle run tag @s add airstrike_shooter
scoreboard players set #sln airstrike 0
execute centered_in_sub_level @e run scoreboard players set #sln airstrike 1
# 1) аппарат, на который смотрит игрок (штатный выбор Create Aeronautics «@v»)
execute if score #sln airstrike matches 1 at @s centered_in_sub_level @v run function airstrike:ray/hit_sl
# 2) иначе — луч по шагам: аппарат рядом с лучом → сущность → блок
scoreboard players set #steps airstrike 0
execute unless entity @e[type=minecraft:marker,tag=airstrike_tgt_new] at @s anchored eyes positioned ^ ^ ^0.5 run function airstrike:ray/step
tag @s remove airstrike_shooter
execute on vehicle run tag @s remove airstrike_shooter
execute if data storage airstrike:tmp sl.px run tellraw @s {"text":"⌖ Цель: аппарат Create — снаряд пойдёт за ним","color":"gold"}
execute if data storage airstrike:tmp sl.id run function airstrike:ray/say_ent with storage airstrike:tmp sl
