tag @s remove shahed_newsurf
scoreboard players set @s shahed_t 0
execute store result score #sy shahed run data get entity @s Pos[1] 100
# глубина взрыва в блоках
scoreboard players operation @s shahed_E = #sy shahed
scoreboard players operation @s shahed_E -= #by shahed
scoreboard players operation @s shahed_E /= #100 shahed
# «труба» обрушения: от свода полости (взрыв + 10) до поверхности
scoreboard players operation #c shahed = #by shahed
scoreboard players add #c shahed 1000
scoreboard players operation #c shahed -= #sy shahed
scoreboard players operation #c shahed /= #100 shahed
execute if score #c shahed matches 0.. run scoreboard players set #c shahed -1
execute store result entity @s data.cy int 1 run scoreboard players get #c shahed
function shahed:debris/sample
function shahed:fx/spray_pick
