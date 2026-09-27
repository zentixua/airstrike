# здесь мы в пространстве аппарата: запоминаем точку попадания в его координатах, цель — её мировое положение
summon minecraft:marker ~ ~ ~ {Tags:["airstrike","airstrike_slp"]}
execute store result storage airstrike:tmp sl.px double 0.01 run data get entity @e[type=minecraft:marker,tag=airstrike_slp,limit=1] Pos[0] 100
execute store result storage airstrike:tmp sl.py double 0.01 run data get entity @e[type=minecraft:marker,tag=airstrike_slp,limit=1] Pos[1] 100
execute store result storage airstrike:tmp sl.pz double 0.01 run data get entity @e[type=minecraft:marker,tag=airstrike_slp,limit=1] Pos[2] 100
kill @e[type=minecraft:marker,tag=airstrike_slp]
execute out_sub_level @i run summon minecraft:marker ~ ~ ~ {Tags:["airstrike","airstrike_tgt_new"]}
execute unless entity @e[type=minecraft:marker,tag=airstrike_tgt_new] out_sub_level @n run summon minecraft:marker ~ ~ ~ {Tags:["airstrike","airstrike_tgt_new"]}
execute unless entity @e[type=minecraft:marker,tag=airstrike_tgt_new] run data remove storage airstrike:tmp sl
return 1
