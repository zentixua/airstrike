# контрольные точки столкновения от носа на длину шага; #nose — полудлина корпуса (сантиблоки)
scoreboard players operation #c shahed = @s shahed_v
scoreboard players set #k shahed 1
scoreboard players operation #c shahed *= #k shahed
scoreboard players set #k shahed 12
scoreboard players operation #c shahed /= #k shahed
scoreboard players operation #c shahed += #nose shahed
execute store result storage shahed:tmp mv.c1 double 0.01 run scoreboard players get #c shahed
scoreboard players operation #c shahed = @s shahed_v
scoreboard players set #k shahed 2
scoreboard players operation #c shahed *= #k shahed
scoreboard players set #k shahed 12
scoreboard players operation #c shahed /= #k shahed
scoreboard players operation #c shahed += #nose shahed
execute store result storage shahed:tmp mv.c2 double 0.01 run scoreboard players get #c shahed
scoreboard players operation #c shahed = @s shahed_v
scoreboard players set #k shahed 3
scoreboard players operation #c shahed *= #k shahed
scoreboard players set #k shahed 12
scoreboard players operation #c shahed /= #k shahed
scoreboard players operation #c shahed += #nose shahed
execute store result storage shahed:tmp mv.c3 double 0.01 run scoreboard players get #c shahed
scoreboard players operation #c shahed = @s shahed_v
scoreboard players set #k shahed 4
scoreboard players operation #c shahed *= #k shahed
scoreboard players set #k shahed 12
scoreboard players operation #c shahed /= #k shahed
scoreboard players operation #c shahed += #nose shahed
execute store result storage shahed:tmp mv.c4 double 0.01 run scoreboard players get #c shahed
scoreboard players operation #c shahed = @s shahed_v
scoreboard players set #k shahed 5
scoreboard players operation #c shahed *= #k shahed
scoreboard players set #k shahed 12
scoreboard players operation #c shahed /= #k shahed
scoreboard players operation #c shahed += #nose shahed
execute store result storage shahed:tmp mv.c5 double 0.01 run scoreboard players get #c shahed
scoreboard players operation #c shahed = @s shahed_v
scoreboard players set #k shahed 6
scoreboard players operation #c shahed *= #k shahed
scoreboard players set #k shahed 12
scoreboard players operation #c shahed /= #k shahed
scoreboard players operation #c shahed += #nose shahed
execute store result storage shahed:tmp mv.c6 double 0.01 run scoreboard players get #c shahed
scoreboard players operation #c shahed = @s shahed_v
scoreboard players set #k shahed 7
scoreboard players operation #c shahed *= #k shahed
scoreboard players set #k shahed 12
scoreboard players operation #c shahed /= #k shahed
scoreboard players operation #c shahed += #nose shahed
execute store result storage shahed:tmp mv.c7 double 0.01 run scoreboard players get #c shahed
scoreboard players operation #c shahed = @s shahed_v
scoreboard players set #k shahed 8
scoreboard players operation #c shahed *= #k shahed
scoreboard players set #k shahed 12
scoreboard players operation #c shahed /= #k shahed
scoreboard players operation #c shahed += #nose shahed
execute store result storage shahed:tmp mv.c8 double 0.01 run scoreboard players get #c shahed
scoreboard players operation #c shahed = @s shahed_v
scoreboard players set #k shahed 9
scoreboard players operation #c shahed *= #k shahed
scoreboard players set #k shahed 12
scoreboard players operation #c shahed /= #k shahed
scoreboard players operation #c shahed += #nose shahed
execute store result storage shahed:tmp mv.c9 double 0.01 run scoreboard players get #c shahed
scoreboard players operation #c shahed = @s shahed_v
scoreboard players set #k shahed 10
scoreboard players operation #c shahed *= #k shahed
scoreboard players set #k shahed 12
scoreboard players operation #c shahed /= #k shahed
scoreboard players operation #c shahed += #nose shahed
execute store result storage shahed:tmp mv.c10 double 0.01 run scoreboard players get #c shahed
scoreboard players operation #c shahed = @s shahed_v
scoreboard players set #k shahed 11
scoreboard players operation #c shahed *= #k shahed
scoreboard players set #k shahed 12
scoreboard players operation #c shahed /= #k shahed
scoreboard players operation #c shahed += #nose shahed
execute store result storage shahed:tmp mv.c11 double 0.01 run scoreboard players get #c shahed
scoreboard players operation #c shahed = @s shahed_v
scoreboard players set #k shahed 12
scoreboard players operation #c shahed *= #k shahed
scoreboard players set #k shahed 12
scoreboard players operation #c shahed /= #k shahed
scoreboard players operation #c shahed += #nose shahed
execute store result storage shahed:tmp mv.c12 double 0.01 run scoreboard players get #c shahed
scoreboard players operation #c shahed = @s shahed_v
scoreboard players set #k shahed 1
scoreboard players operation #c shahed *= #k shahed
scoreboard players set #k shahed 4
scoreboard players operation #c shahed /= #k shahed
scoreboard players operation #c shahed += #nose shahed
execute store result storage shahed:tmp mv.m1 double 0.01 run scoreboard players get #c shahed
scoreboard players operation #c shahed = @s shahed_v
scoreboard players set #k shahed 1
scoreboard players operation #c shahed *= #k shahed
scoreboard players set #k shahed 2
scoreboard players operation #c shahed /= #k shahed
scoreboard players operation #c shahed += #nose shahed
execute store result storage shahed:tmp mv.m2 double 0.01 run scoreboard players get #c shahed
scoreboard players operation #c shahed = @s shahed_v
scoreboard players set #k shahed 3
scoreboard players operation #c shahed *= #k shahed
scoreboard players set #k shahed 4
scoreboard players operation #c shahed /= #k shahed
scoreboard players operation #c shahed += #nose shahed
execute store result storage shahed:tmp mv.m3 double 0.01 run scoreboard players get #c shahed
execute store result storage shahed:tmp mv.v double 0.01 run scoreboard players get @s shahed_v
scoreboard players operation #c shahed = @s shahed_v
scoreboard players set #k shahed 8
scoreboard players operation #c shahed /= #k shahed
scoreboard players add #c shahed 180
execute store result storage shahed:tmp mv.r double 0.01 run scoreboard players get #c shahed
return run function shahed:fly/move_m with storage shahed:tmp mv
