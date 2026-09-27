# маркер без инициализации (сбой спавна) — убрать, чтобы не сыпал ошибками
execute unless score @s airstrike_id matches 1.. run return run kill @s
scoreboard players add @s airstrike_t 1
function airstrike:fly/measure
# сброс за ~85 блоков до цели: бомба сама доворачивает и входит почти отвесно
execute if score @s airstrike_rel matches 0 if score #hd2 airstrike matches ..722500 run function airstrike:bunker/release
execute if score @s airstrike_t matches 120.. run return run function airstrike:bunker/bomber_gone
execute unless loaded ^ ^ ^40 run return run function airstrike:bunker/bomber_gone
tp @s ^ ^ ^12
execute at @s run function airstrike:bunker/bomber_visual
