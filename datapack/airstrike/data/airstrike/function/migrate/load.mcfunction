# Переход со старого имени: до 27.09.2026 датапак назывался shahed.
# Срабатывает один раз — в мире, где ещё лежат настройки shahed:cfg.
execute unless data storage shahed:cfg power run return 0

# настройки
data modify storage airstrike:cfg power set from storage shahed:cfg power
data modify storage airstrike:cfg shatter set from storage shahed:cfg shatter
data modify storage airstrike:cfg shake set from storage shahed:cfg shake
data modify storage airstrike:cfg missile_power set from storage shahed:cfg missile_power
data modify storage airstrike:cfg debris_stay set from storage shahed:cfg debris_stay
data modify storage airstrike:cfg siren set from storage shahed:cfg siren
data modify storage airstrike:cfg bunker_power set from storage shahed:cfg bunker_power
data modify storage airstrike:cfg bunker_energy set from storage shahed:cfg bunker_energy
data modify storage airstrike:cfg collapse set from storage shahed:cfg collapse
data remove storage shahed:cfg power
data remove storage shahed:cfg shatter
data remove storage shahed:cfg shake
data remove storage shahed:cfg missile_power
data remove storage shahed:cfg debris_stay
data remove storage shahed:cfg siren
data remove storage shahed:cfg bunker_power
data remove storage shahed:cfg bunker_energy
data remove storage shahed:cfg collapse
data remove storage shahed:cfg aimdiag

# снаряды и эффекты старой версии остались без хозяина — убрать (и погасить фонари ракет)
execute as @e[type=minecraft:marker,tag=shahed_lamp] at @s if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
kill @e[tag=shahed]
tag @a remove shahed_nv

# старый счёт; выбор звука и пульта (shahed_mode, shahed_seen, shahed_mt, shahed_mn, shahed_mr) остаётся,
# пока каждый игрок не зайдёт — его переносит migrate/player
scoreboard objectives remove shahed
scoreboard objectives remove shahed_own
scoreboard objectives remove shahed_E
scoreboard objectives remove shahed_trav
scoreboard objectives remove shahed_fuse
scoreboard objectives remove shahed_quake
scoreboard objectives remove shahed_rel
scoreboard objectives remove shahed_rp
scoreboard objectives remove shahed_test
scoreboard objectives remove shahed_left
scoreboard objectives remove shahed_sid
scoreboard objectives remove shahed_pd
scoreboard objectives remove shahed_id
scoreboard objectives remove shahed_t
scoreboard objectives remove shahed_ph
scoreboard objectives remove shahed_fb
scoreboard objectives remove shahed_shake
scoreboard objectives remove shahed_w
scoreboard objectives remove shahed_fbid
scoreboard objectives remove shahed_wp
scoreboard objectives remove shahed_wy
scoreboard objectives remove shahed_v
scoreboard objectives remove shahed_h
scoreboard objectives remove shahed_hc
scoreboard objectives remove shahed_tx
scoreboard objectives remove shahed_ty
scoreboard objectives remove shahed_tz
scoreboard objectives remove shahed_pop
scoreboard objectives remove shahed_mat
scoreboard objectives remove shahed_px
scoreboard objectives remove shahed_py
scoreboard objectives remove shahed_pz
scoreboard objectives remove shahed_pd0
scoreboard objectives remove shahed_sid0
scoreboard objectives remove shahed_pd1
scoreboard objectives remove shahed_sid1
scoreboard objectives remove shahed_pd2
scoreboard objectives remove shahed_sid2
scoreboard objectives remove shahed_pd3
scoreboard objectives remove shahed_sid3
scoreboard objectives remove shahed_wsid
scoreboard objectives remove shahed_sirt

# кто онлайн — перенести сразу, остальных — при входе (rp/welcome)
execute as @a run function airstrike:migrate/player
