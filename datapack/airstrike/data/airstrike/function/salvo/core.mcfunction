# контекст: позиция центра, @s — кто запускает; #st — тип; sv.count / sv.r — сколько и разброс
execute store result score #c airstrike run data get storage airstrike:tmp sv.count
execute if score #c airstrike matches ..0 run scoreboard players set #c airstrike 1
execute if score #c airstrike matches 31.. run scoreboard players set #c airstrike 30
execute store result storage airstrike:tmp sv.count int 1 run scoreboard players get #c airstrike
execute store result score #r airstrike run data get storage airstrike:tmp sv.r
execute if score #r airstrike matches ..-1 run scoreboard players set #r airstrike 0
execute if score #r airstrike matches 151.. run scoreboard players set #r airstrike 150
execute store result storage airstrike:tmp sv.r int 1 run scoreboard players get #r airstrike
data modify storage airstrike:tmp sv.yaw set from entity @s Rotation[0]
scoreboard players add #next airstrike_id 1
scoreboard players operation @s airstrike_own = #next airstrike_id
summon minecraft:marker ~ ~ ~ {Tags:["airstrike","airstrike_salvo","airstrike_newsalvo"]}
execute as @e[type=minecraft:marker,tag=airstrike_newsalvo] run function airstrike:salvo/init
execute if data storage airstrike:cfg {siren:1b} unless score #st airstrike matches 2 run function airstrike:drone/siren
execute if data storage airstrike:cfg {siren:1b} if score #st airstrike matches 2 run function airstrike:missile/siren
execute if score #st airstrike matches 1 run tellraw @s ["",{"text":"✈ Залп шахедов: ","color":"red","bold":true},{"storage":"airstrike:tmp","nbt":"sv.count","color":"yellow"},{"text":" шт., разброс ","color":"gray"},{"storage":"airstrike:tmp","nbt":"sv.r","color":"yellow"},{"text":" бл. Подлёт первого ~5 с.","color":"gray"}]
execute if score #st airstrike matches 2 run tellraw @s ["",{"text":"➶ Залп крылатых ракет: ","color":"red","bold":true},{"storage":"airstrike:tmp","nbt":"sv.count","color":"yellow"},{"text":" шт., разброс ","color":"gray"},{"storage":"airstrike:tmp","nbt":"sv.r","color":"yellow"},{"text":" бл. Первая ~7 с.","color":"gray"}]
execute if score #st airstrike matches 3 run tellraw @s ["",{"text":"✹ Налёт B-2: ","color":"red","bold":true},{"storage":"airstrike:tmp","nbt":"sv.count","color":"yellow"},{"text":" бомб, разброс ","color":"gray"},{"storage":"airstrike:tmp","nbt":"sv.r","color":"yellow"},{"text":" бл. Первый заход ~3 с.","color":"gray"}]
