# Airstrike — загрузка: счёт, константы, переход со старого имени, настройки по умолчанию

scoreboard objectives add airstrike dummy

# снаряды: id, время, фаза, скорость, высота, цель, повороты, бомба
scoreboard objectives add airstrike_id dummy
scoreboard objectives add airstrike_t dummy
scoreboard objectives add airstrike_ph dummy
scoreboard objectives add airstrike_v dummy
scoreboard objectives add airstrike_h dummy
scoreboard objectives add airstrike_hc dummy
scoreboard objectives add airstrike_tx dummy
scoreboard objectives add airstrike_ty dummy
scoreboard objectives add airstrike_tz dummy
scoreboard objectives add airstrike_wp dummy
scoreboard objectives add airstrike_wy dummy
scoreboard objectives add airstrike_pop dummy
scoreboard objectives add airstrike_rel dummy
scoreboard objectives add airstrike_fuse dummy
scoreboard objectives add airstrike_E dummy
scoreboard objectives add airstrike_trav dummy

# взрывы и обломки
scoreboard objectives add airstrike_mat dummy
scoreboard objectives add airstrike_shake dummy
scoreboard objectives add airstrike_quake dummy

# звук: кэш позиций игроков, Доплер (4 ячейки), свист, пролёт ракеты, память сирены
scoreboard objectives add airstrike_px dummy
scoreboard objectives add airstrike_py dummy
scoreboard objectives add airstrike_pz dummy
scoreboard objectives add airstrike_pd0 dummy
scoreboard objectives add airstrike_sid0 dummy
scoreboard objectives add airstrike_pd1 dummy
scoreboard objectives add airstrike_sid1 dummy
scoreboard objectives add airstrike_pd2 dummy
scoreboard objectives add airstrike_sid2 dummy
scoreboard objectives add airstrike_pd3 dummy
scoreboard objectives add airstrike_sid3 dummy
scoreboard objectives add airstrike_wsid dummy
scoreboard objectives add airstrike_fbid dummy
scoreboard objectives add airstrike_sirt dummy

# игроки: режим звука, подсказки, пульт (тип / количество / разброс), владелец залпа
scoreboard objectives add airstrike_rp trigger
scoreboard objectives add airstrike_mode dummy
scoreboard objectives add airstrike_seen dummy
scoreboard objectives add airstrike_test dummy
scoreboard objectives add airstrike_left minecraft.custom:minecraft.leave_game
scoreboard objectives add airstrike_mt dummy
scoreboard objectives add airstrike_mn dummy
scoreboard objectives add airstrike_mr dummy
scoreboard objectives add airstrike_own dummy

# константы для арифметики
scoreboard players set #-300 airstrike -300
scoreboard players set #-30 airstrike -30
scoreboard players set #-3 airstrike -3
scoreboard players set #-1 airstrike -1
scoreboard players set #1 airstrike 1
scoreboard players set #2 airstrike 2
scoreboard players set #3 airstrike 3
scoreboard players set #4 airstrike 4
scoreboard players set #5 airstrike 5
scoreboard players set #6 airstrike 6
scoreboard players set #8 airstrike 8
scoreboard players set #10 airstrike 10
scoreboard players set #12 airstrike 12
scoreboard players set #15 airstrike 15
scoreboard players set #19 airstrike 19
scoreboard players set #20 airstrike 20
scoreboard players set #30 airstrike 30
scoreboard players set #32 airstrike 32
scoreboard players set #35 airstrike 35
scoreboard players set #45 airstrike 45
scoreboard players set #60 airstrike 60
scoreboard players set #100 airstrike 100
scoreboard players set #140 airstrike 140
scoreboard players set #300 airstrike 300
scoreboard players set #1600 airstrike 1600
scoreboard players set #1700 airstrike 1700

# до 27.09.2026 датапак назывался shahed — перенести настройки, выбор звука и пульта (один раз)
function airstrike:migrate/load

# настройки по умолчанию (/function airstrike:help)
execute unless data storage airstrike:cfg power run data modify storage airstrike:cfg power set value 12
execute unless data storage airstrike:cfg shatter run data modify storage airstrike:cfg shatter set value 1b
execute unless data storage airstrike:cfg shake run data modify storage airstrike:cfg shake set value 1b
execute unless data storage airstrike:cfg missile_power run data modify storage airstrike:cfg missile_power set value 20
execute unless data storage airstrike:cfg debris_stay run data modify storage airstrike:cfg debris_stay set value 1b
execute unless data storage airstrike:cfg siren run data modify storage airstrike:cfg siren set value 1b
execute unless data storage airstrike:cfg bunker_power run data modify storage airstrike:cfg bunker_power set value 20
execute unless data storage airstrike:cfg bunker_energy run data modify storage airstrike:cfg bunker_energy set value 1000
execute unless data storage airstrike:cfg collapse run data modify storage airstrike:cfg collapse set value 1b
data remove storage airstrike:cfg aimdiag

# то, что уже летит во время /reload, продолжает звучать и следить за целью
tag @e[type=minecraft:marker,tag=airstrike_root] add airstrike_snd
tag @e[type=minecraft:marker,tag=airstrike_fx,scores={airstrike_t=..18}] add airstrike_snd
tag @e[type=minecraft:marker,tag=airstrike_mfx,scores={airstrike_t=..18}] add airstrike_snd

# у кого старый пакет звуков — предложить обновить
execute as @a[scores={airstrike_mode=1}] run function airstrike:rp/prompt
