# /function airstrike:salvo_look {type:"missile",count:4,radius:30} — вокруг точки, куда смотришь
$data modify storage airstrike:tmp sv set value {type:"$(type)",count:$(count),r:$(radius)}
function airstrike:ray/start
data modify storage airstrike:tmp sv.sl set from storage airstrike:tmp sl
execute unless entity @e[type=minecraft:marker,tag=airstrike_tgt_new] run return run tellraw @s {"text":"Цель не найдена","color":"red"}
execute at @e[type=minecraft:marker,tag=airstrike_tgt_new,limit=1] rotated as @s run function airstrike:salvo/begin
kill @e[type=minecraft:marker,tag=airstrike_tgt_new]
