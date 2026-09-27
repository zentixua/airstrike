# маркер без инициализации (сбой спавна) — убрать, чтобы не сыпал ошибками
execute unless score @s shahed_id matches 1.. run return run kill @s
scoreboard players add @s shahed_t 1
function shahed:fly/measure
# сброс за ~85 блоков до цели: бомба сама доворачивает и входит почти отвесно
execute if score @s shahed_rel matches 0 if score #hd2 shahed matches ..722500 run function shahed:bunker/release
execute if score @s shahed_t matches 120.. run return run function shahed:bunker/bomber_gone
execute unless loaded ^ ^ ^40 run return run function shahed:bunker/bomber_gone
tp @s ^ ^ ^12
execute at @s run function shahed:bunker/bomber_visual
