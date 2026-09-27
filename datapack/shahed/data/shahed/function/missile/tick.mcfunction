# маркер без инициализации (сбой спавна) — убрать, чтобы не сыпал ошибками
execute unless score @s shahed_id matches 1.. run return run kill @s
scoreboard players add @s shahed_t 1
execute if score @s shahed_ph matches 9 run return run function shahed:missile/wait
execute if data entity @s data.who run function shahed:track with entity @s data
execute if data entity @s data.sl.px run function shahed:track_slp with entity @s data.sl
execute if data entity @s data.sl.id run function shahed:track_te with entity @s data.sl
function shahed:missile/steer with entity @s data
