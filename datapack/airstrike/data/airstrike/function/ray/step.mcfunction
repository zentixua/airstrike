execute if score #sln airstrike matches 1 in_sub_level @n unless block ~ ~ ~ #airstrike:passable run return run function airstrike:ray/hit_sl
execute if score #steps airstrike matches 3.. positioned ~-0.5 ~-0.5 ~-0.5 if entity @e[dx=0,dy=0,dz=0,tag=!airstrike,tag=!airstrike_shooter,type=!#airstrike:aim_ignore] positioned ~0.5 ~0.5 ~0.5 run return run function airstrike:ray/hit_ent
execute unless block ~ ~ ~ #airstrike:passable run return run summon minecraft:marker ~ ~ ~ {Tags:["airstrike","airstrike_tgt_new"]}
scoreboard players add #steps airstrike 1
execute if score #steps airstrike matches 400.. positioned over motion_blocking_no_leaves run return run summon minecraft:marker ~ ~ ~ {Tags:["airstrike","airstrike_tgt_new"]}
execute positioned ^ ^ ^0.5 run function airstrike:ray/step
