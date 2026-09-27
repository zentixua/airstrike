# маркер без инициализации (сбой спавна) — убрать, чтобы не сыпал ошибками
execute unless score @s shahed_id matches 1.. run return run kill @s
scoreboard players add @s shahed_t 1
execute if score @s shahed_ph matches 5 run return run function shahed:bunker/drill_tick
function shahed:bunker/steer with entity @s data
