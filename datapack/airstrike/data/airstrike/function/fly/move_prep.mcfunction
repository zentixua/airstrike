# контрольные точки столкновения от носа на длину шага; #nose — полудлина корпуса (сантиблоки)
scoreboard players operation #c airstrike = @s airstrike_v
scoreboard players set #k airstrike 1
scoreboard players operation #c airstrike *= #k airstrike
scoreboard players set #k airstrike 12
scoreboard players operation #c airstrike /= #k airstrike
scoreboard players operation #c airstrike += #nose airstrike
execute store result storage airstrike:tmp mv.c1 double 0.01 run scoreboard players get #c airstrike
scoreboard players operation #c airstrike = @s airstrike_v
scoreboard players set #k airstrike 2
scoreboard players operation #c airstrike *= #k airstrike
scoreboard players set #k airstrike 12
scoreboard players operation #c airstrike /= #k airstrike
scoreboard players operation #c airstrike += #nose airstrike
execute store result storage airstrike:tmp mv.c2 double 0.01 run scoreboard players get #c airstrike
scoreboard players operation #c airstrike = @s airstrike_v
scoreboard players set #k airstrike 3
scoreboard players operation #c airstrike *= #k airstrike
scoreboard players set #k airstrike 12
scoreboard players operation #c airstrike /= #k airstrike
scoreboard players operation #c airstrike += #nose airstrike
execute store result storage airstrike:tmp mv.c3 double 0.01 run scoreboard players get #c airstrike
scoreboard players operation #c airstrike = @s airstrike_v
scoreboard players set #k airstrike 4
scoreboard players operation #c airstrike *= #k airstrike
scoreboard players set #k airstrike 12
scoreboard players operation #c airstrike /= #k airstrike
scoreboard players operation #c airstrike += #nose airstrike
execute store result storage airstrike:tmp mv.c4 double 0.01 run scoreboard players get #c airstrike
scoreboard players operation #c airstrike = @s airstrike_v
scoreboard players set #k airstrike 5
scoreboard players operation #c airstrike *= #k airstrike
scoreboard players set #k airstrike 12
scoreboard players operation #c airstrike /= #k airstrike
scoreboard players operation #c airstrike += #nose airstrike
execute store result storage airstrike:tmp mv.c5 double 0.01 run scoreboard players get #c airstrike
scoreboard players operation #c airstrike = @s airstrike_v
scoreboard players set #k airstrike 6
scoreboard players operation #c airstrike *= #k airstrike
scoreboard players set #k airstrike 12
scoreboard players operation #c airstrike /= #k airstrike
scoreboard players operation #c airstrike += #nose airstrike
execute store result storage airstrike:tmp mv.c6 double 0.01 run scoreboard players get #c airstrike
scoreboard players operation #c airstrike = @s airstrike_v
scoreboard players set #k airstrike 7
scoreboard players operation #c airstrike *= #k airstrike
scoreboard players set #k airstrike 12
scoreboard players operation #c airstrike /= #k airstrike
scoreboard players operation #c airstrike += #nose airstrike
execute store result storage airstrike:tmp mv.c7 double 0.01 run scoreboard players get #c airstrike
scoreboard players operation #c airstrike = @s airstrike_v
scoreboard players set #k airstrike 8
scoreboard players operation #c airstrike *= #k airstrike
scoreboard players set #k airstrike 12
scoreboard players operation #c airstrike /= #k airstrike
scoreboard players operation #c airstrike += #nose airstrike
execute store result storage airstrike:tmp mv.c8 double 0.01 run scoreboard players get #c airstrike
scoreboard players operation #c airstrike = @s airstrike_v
scoreboard players set #k airstrike 9
scoreboard players operation #c airstrike *= #k airstrike
scoreboard players set #k airstrike 12
scoreboard players operation #c airstrike /= #k airstrike
scoreboard players operation #c airstrike += #nose airstrike
execute store result storage airstrike:tmp mv.c9 double 0.01 run scoreboard players get #c airstrike
scoreboard players operation #c airstrike = @s airstrike_v
scoreboard players set #k airstrike 10
scoreboard players operation #c airstrike *= #k airstrike
scoreboard players set #k airstrike 12
scoreboard players operation #c airstrike /= #k airstrike
scoreboard players operation #c airstrike += #nose airstrike
execute store result storage airstrike:tmp mv.c10 double 0.01 run scoreboard players get #c airstrike
scoreboard players operation #c airstrike = @s airstrike_v
scoreboard players set #k airstrike 11
scoreboard players operation #c airstrike *= #k airstrike
scoreboard players set #k airstrike 12
scoreboard players operation #c airstrike /= #k airstrike
scoreboard players operation #c airstrike += #nose airstrike
execute store result storage airstrike:tmp mv.c11 double 0.01 run scoreboard players get #c airstrike
scoreboard players operation #c airstrike = @s airstrike_v
scoreboard players set #k airstrike 12
scoreboard players operation #c airstrike *= #k airstrike
scoreboard players set #k airstrike 12
scoreboard players operation #c airstrike /= #k airstrike
scoreboard players operation #c airstrike += #nose airstrike
execute store result storage airstrike:tmp mv.c12 double 0.01 run scoreboard players get #c airstrike
scoreboard players operation #c airstrike = @s airstrike_v
scoreboard players set #k airstrike 1
scoreboard players operation #c airstrike *= #k airstrike
scoreboard players set #k airstrike 4
scoreboard players operation #c airstrike /= #k airstrike
scoreboard players operation #c airstrike += #nose airstrike
execute store result storage airstrike:tmp mv.m1 double 0.01 run scoreboard players get #c airstrike
scoreboard players operation #c airstrike = @s airstrike_v
scoreboard players set #k airstrike 1
scoreboard players operation #c airstrike *= #k airstrike
scoreboard players set #k airstrike 2
scoreboard players operation #c airstrike /= #k airstrike
scoreboard players operation #c airstrike += #nose airstrike
execute store result storage airstrike:tmp mv.m2 double 0.01 run scoreboard players get #c airstrike
scoreboard players operation #c airstrike = @s airstrike_v
scoreboard players set #k airstrike 3
scoreboard players operation #c airstrike *= #k airstrike
scoreboard players set #k airstrike 4
scoreboard players operation #c airstrike /= #k airstrike
scoreboard players operation #c airstrike += #nose airstrike
execute store result storage airstrike:tmp mv.m3 double 0.01 run scoreboard players get #c airstrike
execute store result storage airstrike:tmp mv.v double 0.01 run scoreboard players get @s airstrike_v
scoreboard players operation #c airstrike = @s airstrike_v
scoreboard players set #k airstrike 8
scoreboard players operation #c airstrike /= #k airstrike
scoreboard players add #c airstrike 180
execute store result storage airstrike:tmp mv.r double 0.01 run scoreboard players get #c airstrike
return run function airstrike:fly/move_m with storage airstrike:tmp mv
