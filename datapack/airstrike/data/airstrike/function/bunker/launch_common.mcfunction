execute unless entity @e[type=minecraft:marker,tag=airstrike_tgt_new] run return run tellraw @s {"text":"Цель не найдена","color":"red"}
data remove storage airstrike:tmp d
# точка прицеливания — поверхность над целью; если цель глубже — бомба пробивается к ней
execute at @e[type=minecraft:marker,tag=airstrike_tgt_new,limit=1] positioned over motion_blocking_no_leaves run summon minecraft:marker ~ ~-0.5 ~ {Tags:["airstrike","airstrike_tgt_surf"]}
execute store result storage airstrike:tmp j.x double 0.1 run random value -25..25
execute store result storage airstrike:tmp j.z double 0.1 run random value -25..25
execute as @e[type=minecraft:marker,tag=airstrike_tgt_surf] at @s run function airstrike:bunker/jitter with storage airstrike:tmp j
execute store result storage airstrike:tmp d.tx double 0.01 run data get entity @e[type=minecraft:marker,tag=airstrike_tgt_surf,limit=1] Pos[0] 100
execute store result storage airstrike:tmp d.ty double 0.01 run data get entity @e[type=minecraft:marker,tag=airstrike_tgt_surf,limit=1] Pos[1] 100
execute store result storage airstrike:tmp d.tz double 0.01 run data get entity @e[type=minecraft:marker,tag=airstrike_tgt_surf,limit=1] Pos[2] 100
execute store result storage airstrike:tmp d.gx double 0.01 run data get entity @e[type=minecraft:marker,tag=airstrike_tgt_new,limit=1] Pos[0] 100
execute store result storage airstrike:tmp d.gy double 0.01 run data get entity @e[type=minecraft:marker,tag=airstrike_tgt_new,limit=1] Pos[1] 100
execute store result storage airstrike:tmp d.gz double 0.01 run data get entity @e[type=minecraft:marker,tag=airstrike_tgt_new,limit=1] Pos[2] 100
execute store result score #sy airstrike run data get entity @e[type=minecraft:marker,tag=airstrike_tgt_surf,limit=1] Pos[1] 100
execute store result score #gy airstrike run data get entity @e[type=minecraft:marker,tag=airstrike_tgt_new,limit=1] Pos[1] 100
scoreboard players operation #sy airstrike -= #gy airstrike
data modify storage airstrike:tmp d.goal set value 0b
execute if score #sy airstrike matches 400.. run data modify storage airstrike:tmp d.goal set value 1b
execute unless entity @e[type=minecraft:marker,tag=airstrike_helper] run summon minecraft:marker ~ ~ ~ {Tags:["airstrike","airstrike_helper"]}
execute unless entity @e[type=minecraft:marker,tag=airstrike_helper2] run summon minecraft:marker ~ ~ ~ {Tags:["airstrike","airstrike_helper2"]}
execute at @e[type=minecraft:marker,tag=airstrike_tgt_surf,limit=1] rotated as @s rotated ~ 0 run function airstrike:bunker/bomber_spawn_at
execute if data storage airstrike:cfg {siren:1b} unless score #nosiren airstrike matches 1 at @e[type=minecraft:marker,tag=airstrike_tgt_surf,limit=1] run function airstrike:drone/siren
kill @e[type=minecraft:marker,tag=airstrike_tgt_new]
# бомба бьёт по точке и за целью не следит — пометку с сущности снимаем
execute if data storage airstrike:tmp sl.id run function airstrike:untag_if_free with storage airstrike:tmp sl
kill @e[type=minecraft:marker,tag=airstrike_tgt_surf]
title @s actionbar {"text":"B-2 на боевом курсе. Сброс бетонобойной бомбы через несколько секунд.","color":"red"}
playsound minecraft:ui.button.click master @s ~ ~ ~ 1 0.5
