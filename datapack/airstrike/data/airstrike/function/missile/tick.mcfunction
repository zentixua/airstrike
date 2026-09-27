# маркер без инициализации (сбой спавна) — убрать, чтобы не сыпал ошибками
execute unless score @s airstrike_id matches 1.. run return run kill @s
scoreboard players add @s airstrike_t 1
execute if score @s airstrike_ph matches 9 run return run function airstrike:missile/wait
execute if data entity @s data.who run function airstrike:track with entity @s data
execute if data entity @s data.sl.px run function airstrike:track_slp with entity @s data.sl
execute if data entity @s data.sl.id run function airstrike:track_te with entity @s data.sl
function airstrike:missile/steer with entity @s data
