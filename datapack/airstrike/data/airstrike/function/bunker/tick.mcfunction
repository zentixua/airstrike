# маркер без инициализации (сбой спавна) — убрать, чтобы не сыпал ошибками
execute unless score @s airstrike_id matches 1.. run return run kill @s
scoreboard players add @s airstrike_t 1
execute if score @s airstrike_ph matches 5 run return run function airstrike:bunker/drill_tick
function airstrike:bunker/steer with entity @s data
