# строка над хотбаром у того, кто запустил
scoreboard players operation #own shahed = @s shahed_id
scoreboard players operation #done shahed = @s shahed_trav
scoreboard players operation #done shahed -= @s shahed_E
execute store result storage shahed:tmp hud.d int 1 run scoreboard players get #done shahed
execute store result storage shahed:tmp hud.n int 1 run scoreboard players get @s shahed_trav
execute as @a if score @s shahed_own = #own shahed run title @s actionbar ["",{"text":"ПУСК ","color":"red","bold":true},{"storage":"shahed:tmp","nbt":"hud.d","color":"yellow"},{"text":" / ","color":"gray"},{"storage":"shahed:tmp","nbt":"hud.n","color":"yellow"}]
