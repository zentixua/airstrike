execute if score #sln shahed matches 1 in_sub_level @n unless block ~ ~ ~ #shahed:passable run return run function shahed:ray/hit_sl
execute if score #steps shahed matches 3.. positioned ~-0.5 ~-0.5 ~-0.5 if entity @e[dx=0,dy=0,dz=0,tag=!shahed,tag=!shahed_shooter,type=!#shahed:aim_ignore] positioned ~0.5 ~0.5 ~0.5 run return run function shahed:ray/hit_ent
execute unless block ~ ~ ~ #shahed:passable run return run summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_tgt_new"]}
scoreboard players add #steps shahed 1
execute if score #steps shahed matches 400.. positioned over motion_blocking_no_leaves run return run summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_tgt_new"]}
execute positioned ^ ^ ^0.5 run function shahed:ray/step
