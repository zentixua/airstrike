# Залп: N шахедов / ракет / бомб по случайным точкам в круге радиуса R вокруг тебя (или точки взгляда).
import os, sys
FN = sys.argv[1] + "/data/shahed/function"
def rd(n): return open(f"{FN}/{n}.mcfunction", encoding="utf-8").read()
def wr(n, t):
    p = f"{FN}/{n}.mcfunction"; os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w", encoding="utf-8").write(t.strip("\n") + "\n")

# одна сирена на весь залп
for n in ("launch_common", "missile/launch_common", "bunker/launch_common"):
    s = rd(n)
    if "#nosiren" not in s:
        s = s.replace("execute if data storage shahed:cfg {siren:1b} at", "execute if data storage shahed:cfg {siren:1b} unless score #nosiren shahed matches 1 at")
        assert "#nosiren" in s, n
        wr(n, s)

wr("salvo", """
# /function shahed:salvo {type:"drone",count:6,radius:25} — залп вокруг того места, где ты стоишь
# type: drone / missile / bunker (или шахед / ракета / бомба)
$data modify storage shahed:tmp sv set value {type:"$(type)",count:$(count),r:$(radius)}
execute at @s run function shahed:salvo/begin
""")
wr("salvo_look", """
# /function shahed:salvo_look {type:"missile",count:4,radius:30} — то же, но вокруг точки, куда смотришь
$data modify storage shahed:tmp sv set value {type:"$(type)",count:$(count),r:$(radius)}
function shahed:ray/start
execute unless entity @e[type=minecraft:marker,tag=shahed_tgt_new] run return run tellraw @s {"text":"Цель не найдена","color":"red"}
execute at @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] rotated as @s run function shahed:salvo/begin
kill @e[type=minecraft:marker,tag=shahed_tgt_new]
""")
wr("salvo/begin", """
scoreboard players set #st shahed 0
execute if data storage shahed:tmp sv{type:"drone"} run scoreboard players set #st shahed 1
execute if data storage shahed:tmp sv{type:"shahed"} run scoreboard players set #st shahed 1
execute if data storage shahed:tmp sv{type:"шахед"} run scoreboard players set #st shahed 1
execute if data storage shahed:tmp sv{type:"missile"} run scoreboard players set #st shahed 2
execute if data storage shahed:tmp sv{type:"ракета"} run scoreboard players set #st shahed 2
execute if data storage shahed:tmp sv{type:"bunker"} run scoreboard players set #st shahed 3
execute if data storage shahed:tmp sv{type:"бомба"} run scoreboard players set #st shahed 3
execute if score #st shahed matches 0 run return run tellraw @s {"text":"Тип залпа: drone, missile или bunker","color":"red"}
data modify storage shahed:tmp sv.yaw set from entity @s Rotation[0]
summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_salvo","shahed_newsalvo"]}
execute as @e[type=minecraft:marker,tag=shahed_newsalvo] run function shahed:salvo/init
execute if data storage shahed:cfg {siren:1b} if score #st shahed matches 1 run function shahed:drone/siren
execute if data storage shahed:cfg {siren:1b} if score #st shahed matches 2 run function shahed:missile/siren
execute if data storage shahed:cfg {siren:1b} if score #st shahed matches 3 run function shahed:drone/siren
execute if score #st shahed matches 1 run tellraw @s ["",{"text":"Залп шахедов: ","color":"red","bold":true},{"storage":"shahed:tmp","nbt":"sv.count","color":"yellow"},{"text":" шт., разброс ","color":"gray"},{"storage":"shahed:tmp","nbt":"sv.r","color":"yellow"},{"text":" блоков. Идут с интервалом 1–2 с.","color":"gray"}]
execute if score #st shahed matches 2 run tellraw @s ["",{"text":"Залп крылатых ракет: ","color":"red","bold":true},{"storage":"shahed:tmp","nbt":"sv.count","color":"yellow"},{"text":" шт., разброс ","color":"gray"},{"storage":"shahed:tmp","nbt":"sv.r","color":"yellow"},{"text":" блоков. Первая — примерно через 7 с.","color":"gray"}]
execute if score #st shahed matches 3 run tellraw @s ["",{"text":"Налёт B-2: ","color":"red","bold":true},{"storage":"shahed:tmp","nbt":"sv.count","color":"yellow"},{"text":" бомб, разброс ","color":"gray"},{"storage":"shahed:tmp","nbt":"sv.r","color":"yellow"},{"text":" блоков. Бомбардировщики заходят каждые 3–4 с.","color":"gray"}]
""")
wr("salvo/init", """
tag @s remove shahed_newsalvo
scoreboard players operation @s shahed_ph = #st shahed
execute store result score @s shahed_E run data get storage shahed:tmp sv.count
execute if score @s shahed_E matches ..0 run scoreboard players set @s shahed_E 1
execute if score @s shahed_E matches 31.. run scoreboard players set @s shahed_E 30
execute store result score #r shahed run data get storage shahed:tmp sv.r
execute if score #r shahed matches ..2 run scoreboard players set #r shahed 3
execute if score #r shahed matches 151.. run scoreboard players set #r shahed 150
execute store result entity @s data.r int 1 run scoreboard players get #r shahed
data modify entity @s data.yaw set from storage shahed:tmp sv.yaw
scoreboard players set @s shahed_t 1
""")
wr("salvo/tick", """
scoreboard players remove @s shahed_t 1
execute if score @s shahed_t matches 1.. run return 0
execute if score @s shahed_E matches ..0 run return run kill @s
function shahed:salvo/fire
scoreboard players remove @s shahed_E 1
execute if score @s shahed_ph matches 1 store result score @s shahed_t run random value 20..40
execute if score @s shahed_ph matches 2 store result score @s shahed_t run random value 15..30
execute if score @s shahed_ph matches 3 store result score @s shahed_t run random value 60..80
""")
wr("salvo/fire", """
# случайная точка в круге (равномерно по площади), цель — поверхность
data modify storage shahed:tmp sv2.r set from entity @s data.r
scoreboard players set #try shahed 0
function shahed:salvo/pick with storage shahed:tmp sv2
kill @e[type=minecraft:marker,tag=shahed_tgt_new]
function shahed:salvo/place with storage shahed:tmp sp2
# заход с разных сторон: общий курс ± 35°
execute store result score #yb shahed run data get entity @s data.yaw 100
execute store result score #yo shahed run random value -3500..3500
scoreboard players operation #yb shahed += #yo shahed
execute store result entity @s Rotation[0] float 0.01 run scoreboard players get #yb shahed
scoreboard players set #nosiren shahed 1
execute if score @s shahed_ph matches 1 run function shahed:launch_common
execute if score @s shahed_ph matches 2 run function shahed:missile/launch_common
execute if score @s shahed_ph matches 3 run function shahed:bunker/launch_common
scoreboard players set #nosiren shahed 0
kill @e[type=minecraft:marker,tag=shahed_tgt_new]
""")
wr("salvo/pick", """
$execute store result score #dx shahed run random value -$(r)..$(r)
$execute store result score #dz shahed run random value -$(r)..$(r)
$scoreboard players set #r2 shahed $(r)
scoreboard players operation #r2 shahed *= #r2 shahed
scoreboard players operation #q shahed = #dx shahed
scoreboard players operation #q shahed *= #dx shahed
scoreboard players operation #q2 shahed = #dz shahed
scoreboard players operation #q2 shahed *= #dz shahed
scoreboard players operation #q shahed += #q2 shahed
scoreboard players add #try shahed 1
execute if score #q shahed > #r2 shahed if score #try shahed matches ..8 run return run function shahed:salvo/pick with storage shahed:tmp sv2
execute store result storage shahed:tmp sp2.x int 1 run scoreboard players get #dx shahed
execute store result storage shahed:tmp sp2.z int 1 run scoreboard players get #dz shahed
""")
wr("salvo/place", "$execute positioned ~$(x) ~ ~$(z) positioned over motion_blocking_no_leaves run summon minecraft:marker ~ ~-0.5 ~ {Tags:[\"shahed_tgt_new\"]}")

t = rd("tick")
if "salvo/tick" not in t:
    wr("tick", t + "\nexecute as @e[type=minecraft:marker,tag=shahed_salvo] at @s run function shahed:salvo/tick")
h = rd("help")
if "salvo" not in h:
    h = h.replace('tellraw @s [{"text":"/function shahed:clear"',
        'tellraw @s [{"text":"/function shahed:salvo {type:\\"drone\\",count:6,radius:25}","color":"yellow"},{"text":" — залп вокруг того места, где стоишь (drone / missile / bunker)","color":"gray"}]\n'
        'tellraw @s [{"text":"/function shahed:salvo_look {type:\\"missile\\",count:4,radius:30}","color":"yellow"},{"text":" — залп вокруг точки, куда смотришь","color":"gray"}]\n'
        'tellraw @s [{"text":"/function shahed:clear"')
    wr("help", h)
c = rd("clear").replace("Все шахеды и ракеты убраны.", "Все шахеды, ракеты, бомбы и залпы убраны.")
wr("clear", c)
print("ok")
