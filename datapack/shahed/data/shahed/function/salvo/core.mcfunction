# контекст: позиция центра, @s — кто запускает; #st — тип; sv.count / sv.r — сколько и разброс
execute store result score #c shahed run data get storage shahed:tmp sv.count
execute if score #c shahed matches ..0 run scoreboard players set #c shahed 1
execute if score #c shahed matches 31.. run scoreboard players set #c shahed 30
execute store result storage shahed:tmp sv.count int 1 run scoreboard players get #c shahed
execute store result score #r shahed run data get storage shahed:tmp sv.r
execute if score #r shahed matches ..-1 run scoreboard players set #r shahed 0
execute if score #r shahed matches 151.. run scoreboard players set #r shahed 150
execute store result storage shahed:tmp sv.r int 1 run scoreboard players get #r shahed
data modify storage shahed:tmp sv.yaw set from entity @s Rotation[0]
scoreboard players add #next shahed_id 1
scoreboard players operation @s shahed_own = #next shahed_id
summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_salvo","shahed_newsalvo"]}
execute as @e[type=minecraft:marker,tag=shahed_newsalvo] run function shahed:salvo/init
execute if data storage shahed:cfg {siren:1b} unless score #st shahed matches 2 run function shahed:drone/siren
execute if data storage shahed:cfg {siren:1b} if score #st shahed matches 2 run function shahed:missile/siren
execute if score #st shahed matches 1 run tellraw @s ["",{"text":"✈ Залп шахедов: ","color":"red","bold":true},{"storage":"shahed:tmp","nbt":"sv.count","color":"yellow"},{"text":" шт., разброс ","color":"gray"},{"storage":"shahed:tmp","nbt":"sv.r","color":"yellow"},{"text":" бл. Подлёт первого ~5 с.","color":"gray"}]
execute if score #st shahed matches 2 run tellraw @s ["",{"text":"➶ Залп крылатых ракет: ","color":"red","bold":true},{"storage":"shahed:tmp","nbt":"sv.count","color":"yellow"},{"text":" шт., разброс ","color":"gray"},{"storage":"shahed:tmp","nbt":"sv.r","color":"yellow"},{"text":" бл. Первая ~7 с.","color":"gray"}]
execute if score #st shahed matches 3 run tellraw @s ["",{"text":"✹ Налёт B-2: ","color":"red","bold":true},{"storage":"shahed:tmp","nbt":"sv.count","color":"yellow"},{"text":" бомб, разброс ","color":"gray"},{"storage":"shahed:tmp","nbt":"sv.r","color":"yellow"},{"text":" бл. Первый заход ~3 с.","color":"gray"}]
