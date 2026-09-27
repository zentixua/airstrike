# /function shahed:salvo_look {type:"missile",count:4,radius:30} — вокруг точки, куда смотришь
$data modify storage shahed:tmp sv set value {type:"$(type)",count:$(count),r:$(radius)}
function shahed:ray/start
data modify storage shahed:tmp sv.sl set from storage shahed:tmp sl
execute unless entity @e[type=minecraft:marker,tag=shahed_tgt_new] run return run tellraw @s {"text":"Цель не найдена","color":"red"}
execute at @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] rotated as @s run function shahed:salvo/begin
kill @e[type=minecraft:marker,tag=shahed_tgt_new]
