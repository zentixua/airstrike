# игроки: подсказки про звук, тряска камеры — всегда
execute as @a unless score @s airstrike_seen matches 1 run function airstrike:rp/welcome
execute as @a[scores={airstrike_left=1..}] run function airstrike:rp/rejoin
execute as @a[scores={airstrike_rp=1..}] at @s run function airstrike:rp/handle
execute as @a[scores={airstrike_test=1..}] at @s run function airstrike:rp/test_tick
execute as @a[scores={airstrike_shake=1..}] at @s run function airstrike:fx/shake
execute as @a[scores={airstrike_quake=1..}] at @s run function airstrike:bfx/quake

# ничего нашего в мире нет (кроме служебных маркеров) — дальше не считаем
execute unless entity @e[tag=airstrike,tag=!airstrike_helper,tag=!airstrike_helper2] run return 0

# позиции игроков — один раз за тик, пока что-то летит или звучит (звук и слежение берут отсюда)
execute if entity @e[type=minecraft:marker,tag=airstrike_snd] as @a run function airstrike:snd/cache

execute as @e[type=minecraft:marker,tag=airstrike_root,tag=!airstrike_missile,tag=!airstrike_bunker,tag=!airstrike_bomber] at @s run function airstrike:drone/tick
execute as @e[type=minecraft:marker,tag=airstrike_root,tag=airstrike_missile] at @s run function airstrike:missile/tick
execute as @e[type=minecraft:marker,tag=airstrike_root,tag=airstrike_bomber] at @s run function airstrike:bunker/bomber_tick
execute as @e[type=minecraft:marker,tag=airstrike_root,tag=airstrike_bunker] at @s run function airstrike:bunker/tick
execute as @e[type=minecraft:marker,tag=airstrike_fx] at @s run function airstrike:fx/tick
execute as @e[type=minecraft:marker,tag=airstrike_mfx] at @s run function airstrike:mfx/tick
execute as @e[type=minecraft:marker,tag=airstrike_bfx] at @s run function airstrike:bfx/tick
execute as @e[type=minecraft:marker,tag=airstrike_vent_on] at @s run function airstrike:bfx/vent_tick
execute as @e[type=minecraft:marker,tag=airstrike_surf] at @s run function airstrike:bfx/surf_tick
execute as @e[type=minecraft:falling_block,tag=airstrike_debris] at @s run function airstrike:fx/debris_trail
execute as @e[type=minecraft:marker,tag=airstrike_salvo] at @s run function airstrike:salvo/tick

scoreboard players add #clk airstrike 1
execute if score #clk airstrike matches 20.. run function airstrike:drone/gc
