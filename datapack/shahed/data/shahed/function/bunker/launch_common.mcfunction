execute unless entity @e[type=minecraft:marker,tag=shahed_tgt_new] run return run tellraw @s {"text":"Цель не найдена","color":"red"}
data remove storage shahed:tmp d
# точка прицеливания — поверхность над целью; если цель глубже — бомба пробивается к ней
execute at @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] positioned over motion_blocking_no_leaves run summon minecraft:marker ~ ~-0.5 ~ {Tags:["shahed","shahed_tgt_surf"]}
execute store result storage shahed:tmp j.x double 0.1 run random value -25..25
execute store result storage shahed:tmp j.z double 0.1 run random value -25..25
execute as @e[type=minecraft:marker,tag=shahed_tgt_surf] at @s run function shahed:bunker/jitter with storage shahed:tmp j
execute store result storage shahed:tmp d.tx double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_tgt_surf,limit=1] Pos[0] 100
execute store result storage shahed:tmp d.ty double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_tgt_surf,limit=1] Pos[1] 100
execute store result storage shahed:tmp d.tz double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_tgt_surf,limit=1] Pos[2] 100
execute store result storage shahed:tmp d.gx double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] Pos[0] 100
execute store result storage shahed:tmp d.gy double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] Pos[1] 100
execute store result storage shahed:tmp d.gz double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] Pos[2] 100
execute store result score #sy shahed run data get entity @e[type=minecraft:marker,tag=shahed_tgt_surf,limit=1] Pos[1] 100
execute store result score #gy shahed run data get entity @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] Pos[1] 100
scoreboard players operation #sy shahed -= #gy shahed
data modify storage shahed:tmp d.goal set value 0b
execute if score #sy shahed matches 400.. run data modify storage shahed:tmp d.goal set value 1b
execute unless entity @e[type=minecraft:marker,tag=shahed_helper] run summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_helper"]}
execute unless entity @e[type=minecraft:marker,tag=shahed_helper2] run summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_helper2"]}
execute at @e[type=minecraft:marker,tag=shahed_tgt_surf,limit=1] rotated as @s rotated ~ 0 run function shahed:bunker/bomber_spawn_at
execute if data storage shahed:cfg {siren:1b} unless score #nosiren shahed matches 1 at @e[type=minecraft:marker,tag=shahed_tgt_surf,limit=1] run function shahed:drone/siren
kill @e[type=minecraft:marker,tag=shahed_tgt_new]
# бомба бьёт по точке и за целью не следит — пометку с сущности снимаем
execute if data storage shahed:tmp sl.id run function shahed:untag_if_free with storage shahed:tmp sl
kill @e[type=minecraft:marker,tag=shahed_tgt_surf]
title @s actionbar {"text":"B-2 на боевом курсе. Сброс бетонобойной бомбы через несколько секунд.","color":"red"}
playsound minecraft:ui.button.click master @s ~ ~ ~ 1 0.5
