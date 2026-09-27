# попали в сущность (игрок, моб, самолёт, поезд или механизм Create): метим её, запоминаем смещение точки попадания
scoreboard players add #tn shahed 1
execute store result storage shahed:tmp sl.id int 1 run scoreboard players get #tn shahed
function shahed:ray/tag_ent with storage shahed:tmp sl
summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_tgt_new"]}
function shahed:ray/offset with storage shahed:tmp sl
return 1
