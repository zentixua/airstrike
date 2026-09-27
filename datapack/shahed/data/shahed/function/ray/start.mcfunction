# луч от глаз: аппарат Create → сущность → блок; что встретится первым, то и цель
kill @e[type=minecraft:marker,tag=shahed_tgt_new]
data remove storage shahed:tmp sl
tag @s add shahed_shooter
execute on vehicle run tag @s add shahed_shooter
scoreboard players set #sln shahed 0
execute centered_in_sub_level @e run scoreboard players set #sln shahed 1
# 1) аппарат, на который смотрит игрок (штатный выбор Create Aeronautics «@v»)
execute if score #sln shahed matches 1 at @s centered_in_sub_level @v run function shahed:ray/hit_sl
# 2) иначе — луч по шагам: аппарат рядом с лучом → сущность → блок
scoreboard players set #steps shahed 0
execute unless entity @e[type=minecraft:marker,tag=shahed_tgt_new] at @s anchored eyes positioned ^ ^ ^0.5 run function shahed:ray/step
tag @s remove shahed_shooter
execute on vehicle run tag @s remove shahed_shooter
execute if data storage shahed:tmp sl.px run tellraw @s {"text":"⌖ Цель: аппарат Create — снаряд пойдёт за ним","color":"gold"}
execute if data storage shahed:tmp sl.id run function shahed:ray/say_ent with storage shahed:tmp sl
