tag @s remove airstrike_newsurf
scoreboard players set @s airstrike_t 0
execute store result score #sy airstrike run data get entity @s Pos[1] 100
# глубина взрыва в блоках
scoreboard players operation @s airstrike_E = #sy airstrike
scoreboard players operation @s airstrike_E -= #by airstrike
scoreboard players operation @s airstrike_E /= #100 airstrike
# «труба» обрушения: от свода полости (взрыв + 10) до поверхности
scoreboard players operation #c airstrike = #by airstrike
scoreboard players add #c airstrike 1000
scoreboard players operation #c airstrike -= #sy airstrike
scoreboard players operation #c airstrike /= #100 airstrike
execute if score #c airstrike matches 0.. run scoreboard players set #c airstrike -1
execute store result entity @s data.cy int 1 run scoreboard players get #c airstrike
function airstrike:debris/sample
function airstrike:fx/spray_pick
