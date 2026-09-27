scoreboard objectives add shahed dummy
scoreboard objectives add shahed_mt dummy
scoreboard objectives add shahed_mn dummy
scoreboard objectives add shahed_mr dummy
scoreboard objectives add shahed_own dummy
scoreboard objectives add shahed_E dummy
scoreboard objectives add shahed_trav dummy
scoreboard objectives add shahed_fuse dummy
scoreboard objectives add shahed_quake dummy
scoreboard objectives add shahed_rel dummy
scoreboard objectives add shahed_rp trigger
scoreboard objectives add shahed_mode dummy
scoreboard objectives add shahed_seen dummy
scoreboard objectives add shahed_test dummy
scoreboard objectives add shahed_left minecraft.custom:minecraft.leave_game
scoreboard objectives add shahed_sid dummy
scoreboard objectives add shahed_pd dummy
scoreboard objectives add shahed_id dummy
scoreboard objectives add shahed_t dummy
scoreboard objectives add shahed_ph dummy
scoreboard objectives add shahed_fb dummy
scoreboard objectives add shahed_shake dummy
scoreboard objectives add shahed_w dummy
scoreboard objectives add shahed_fbid dummy
scoreboard objectives add shahed_wp dummy
scoreboard objectives add shahed_wy dummy
scoreboard objectives add shahed_v dummy
scoreboard objectives add shahed_h dummy
scoreboard objectives add shahed_hc dummy
scoreboard objectives add shahed_tx dummy
scoreboard objectives add shahed_ty dummy
scoreboard objectives add shahed_tz dummy
scoreboard objectives add shahed_pop dummy
scoreboard objectives add shahed_mat dummy
scoreboard players set #1 shahed 1
scoreboard players set #10 shahed 10
scoreboard players set #12 shahed 12
scoreboard players set #15 shahed 15
scoreboard players set #20 shahed 20
scoreboard players set #32 shahed 32
scoreboard players set #100 shahed 100
scoreboard players set #6 shahed 6
scoreboard players set #4 shahed 4
scoreboard players set #3 shahed 3
scoreboard players set #300 shahed 300
scoreboard players set #-300 shahed -300
scoreboard players set #30 shahed 30
scoreboard players set #-30 shahed -30
scoreboard players set #2 shahed 2
scoreboard players set #8 shahed 8
scoreboard players set #19 shahed 19
scoreboard players set #1700 shahed 1700
scoreboard players set #5 shahed 5
scoreboard players set #35 shahed 35
scoreboard players set #45 shahed 45
scoreboard players set #60 shahed 60
scoreboard players set #-1 shahed -1
execute unless data storage shahed:cfg power run data modify storage shahed:cfg power set value 12
execute unless data storage shahed:cfg shatter run data modify storage shahed:cfg shatter set value 1b
execute unless data storage shahed:cfg shake run data modify storage shahed:cfg shake set value 1b
execute unless data storage shahed:cfg missile_power run data modify storage shahed:cfg missile_power set value 20
execute unless data storage shahed:cfg debris_stay run data modify storage shahed:cfg debris_stay set value 1b
execute unless data storage shahed:cfg siren run data modify storage shahed:cfg siren set value 1b
execute unless data storage shahed:cfg bunker_power run data modify storage shahed:cfg bunker_power set value 20
execute unless data storage shahed:cfg bunker_energy run data modify storage shahed:cfg bunker_energy set value 1000
execute unless data storage shahed:cfg collapse run data modify storage shahed:cfg collapse set value 1b
# новая версия пакета звуков: у кого стоит старая — попросить обновить
execute as @a[scores={shahed_mode=1}] run function shahed:rp/prompt
scoreboard players set #-3 shahed -3
scoreboard players set #140 shahed 140
scoreboard players set #1600 shahed 1600
data remove storage shahed:cfg aimdiag
scoreboard objectives add shahed_px dummy
scoreboard objectives add shahed_py dummy
scoreboard objectives add shahed_pz dummy
scoreboard objectives add shahed_pd0 dummy
scoreboard objectives add shahed_sid0 dummy
scoreboard objectives add shahed_pd1 dummy
scoreboard objectives add shahed_sid1 dummy
scoreboard objectives add shahed_pd2 dummy
scoreboard objectives add shahed_sid2 dummy
scoreboard objectives add shahed_pd3 dummy
scoreboard objectives add shahed_sid3 dummy
scoreboard objectives add shahed_wsid dummy
scoreboard objectives add shahed_sirt dummy
# то, что уже летит во время /reload, продолжает звучать и следить за целью
tag @e[type=minecraft:marker,tag=shahed_root] add shahed_snd
tag @e[type=minecraft:marker,tag=shahed_fx,scores={shahed_t=..18}] add shahed_snd
tag @e[type=minecraft:marker,tag=shahed_mfx,scores={shahed_t=..18}] add shahed_snd
