# попали в сущность (игрок, моб, самолёт, поезд или механизм Create): метим её, запоминаем смещение точки попадания
scoreboard players add #tn airstrike 1
execute store result storage airstrike:tmp sl.id int 1 run scoreboard players get #tn airstrike
function airstrike:ray/tag_ent with storage airstrike:tmp sl
summon minecraft:marker ~ ~ ~ {Tags:["airstrike","airstrike_tgt_new"]}
function airstrike:ray/offset with storage airstrike:tmp sl
return 1
