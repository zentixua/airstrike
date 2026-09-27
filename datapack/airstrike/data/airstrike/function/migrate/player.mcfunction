# Перенос выбора звука и пульта игрока со старого имени shahed.
# Вызывается из migrate/load (кто онлайн) и rp/welcome (кто зайдёт позже).
execute if score @s shahed_mode = @s shahed_mode run scoreboard players operation @s airstrike_mode = @s shahed_mode
execute if score @s shahed_mt = @s shahed_mt run scoreboard players operation @s airstrike_mt = @s shahed_mt
execute if score @s shahed_mn = @s shahed_mn run scoreboard players operation @s airstrike_mn = @s shahed_mn
execute if score @s shahed_mr = @s shahed_mr run scoreboard players operation @s airstrike_mr = @s shahed_mr
execute unless score @s airstrike_mode matches 0.. run scoreboard players set @s airstrike_mode 0
scoreboard players set @s airstrike_seen 1
scoreboard players enable @s airstrike_rp
scoreboard players reset @s shahed_mode
scoreboard players reset @s shahed_seen
scoreboard players reset @s shahed_mt
scoreboard players reset @s shahed_mn
scoreboard players reset @s shahed_mr
tellraw @s ["",{"text":"✈ Airstrike","color":"gold","bold":true},{"text":" — новое имя датапака: ","color":"gray"},{"text":"/function airstrike:menu","color":"yellow","clickEvent":{"action":"run_command","value":"/function airstrike:menu"},"hoverEvent":{"action":"show_text","contents":"Открыть пульт"}},{"text":", пакет звуков — Airstrike Sounds. Твои настройки звука и пульта перенесены.","color":"gray"}]
