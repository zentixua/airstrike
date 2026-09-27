# строка над хотбаром у того, кто запустил
scoreboard players operation #own airstrike = @s airstrike_id
scoreboard players operation #done airstrike = @s airstrike_trav
scoreboard players operation #done airstrike -= @s airstrike_E
execute store result storage airstrike:tmp hud.d int 1 run scoreboard players get #done airstrike
execute store result storage airstrike:tmp hud.n int 1 run scoreboard players get @s airstrike_trav
execute as @a if score @s airstrike_own = #own airstrike run title @s actionbar ["",{"text":"ПУСК ","color":"red","bold":true},{"storage":"airstrike:tmp","nbt":"hud.d","color":"yellow"},{"text":" / ","color":"gray"},{"storage":"airstrike:tmp","nbt":"hud.n","color":"yellow"}]
