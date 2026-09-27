# игроки: подсказки про звук, тряска камеры — всегда
execute as @a unless score @s shahed_seen matches 1 run function shahed:rp/welcome
execute as @a[scores={shahed_left=1..}] run function shahed:rp/rejoin
execute as @a[scores={shahed_rp=1..}] at @s run function shahed:rp/handle
execute as @a[scores={shahed_test=1..}] at @s run function shahed:rp/test_tick
execute as @a[scores={shahed_shake=1..}] at @s run function shahed:fx/shake
execute as @a[scores={shahed_quake=1..}] at @s run function shahed:bfx/quake

# ничего нашего в мире нет (кроме служебных маркеров) — дальше не считаем
execute unless entity @e[tag=shahed,tag=!shahed_helper,tag=!shahed_helper2] run return 0

# позиции игроков — один раз за тик, пока что-то летит или звучит (звук и слежение берут отсюда)
execute if entity @e[type=minecraft:marker,tag=shahed_snd] as @a run function shahed:snd/cache

execute as @e[type=minecraft:marker,tag=shahed_root,tag=!shahed_missile,tag=!shahed_bunker,tag=!shahed_bomber] at @s run function shahed:drone/tick
execute as @e[type=minecraft:marker,tag=shahed_root,tag=shahed_missile] at @s run function shahed:missile/tick
execute as @e[type=minecraft:marker,tag=shahed_root,tag=shahed_bomber] at @s run function shahed:bunker/bomber_tick
execute as @e[type=minecraft:marker,tag=shahed_root,tag=shahed_bunker] at @s run function shahed:bunker/tick
execute as @e[type=minecraft:marker,tag=shahed_fx] at @s run function shahed:fx/tick
execute as @e[type=minecraft:marker,tag=shahed_mfx] at @s run function shahed:mfx/tick
execute as @e[type=minecraft:marker,tag=shahed_bfx] at @s run function shahed:bfx/tick
execute as @e[type=minecraft:marker,tag=shahed_vent_on] at @s run function shahed:bfx/vent_tick
execute as @e[type=minecraft:marker,tag=shahed_surf] at @s run function shahed:bfx/surf_tick
execute as @e[type=minecraft:falling_block,tag=shahed_debris] at @s run function shahed:fx/debris_trail
execute as @e[type=minecraft:marker,tag=shahed_salvo] at @s run function shahed:salvo/tick

scoreboard players add #clk shahed 1
execute if score #clk shahed matches 20.. run function shahed:drone/gc
