# Мультиплеер: у кого нет пакета звуков — звучат звуки из модов (есть у всех),
# у кого есть — полноценные. Каждый вошедший получает подсказку и кнопки проверки.
import os, sys
FN = sys.argv[1]
def rd(n): return open(f"{FN}/{n}.mcfunction", encoding="utf-8").read()
def wr(n, t):
    p = f"{FN}/{n}.mcfunction"; os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w", encoding="utf-8").write(t.strip("\n") + "\n")

t = rd("load")
if "shahed_mode" not in t:
    t = t.replace("scoreboard objectives add shahed dummy\n",
        "scoreboard objectives add shahed dummy\nscoreboard objectives add shahed_rp trigger\nscoreboard objectives add shahed_mode dummy\n"
        "scoreboard objectives add shahed_seen dummy\nscoreboard objectives add shahed_test dummy\n"
        "scoreboard objectives add shahed_left minecraft.custom:minecraft.leave_game\n")
    t += "scoreboard players set #6 shahed 6\n"
    wr("load", t)
t = rd("tick")
if "rp/welcome" not in t:
    t += """
# кто зашёл впервые или перезашёл — подсказка про звук; кнопки проверки
execute as @a unless score @s shahed_seen matches 1 run function shahed:rp/welcome
execute as @a[scores={shahed_left=1..}] run function shahed:rp/rejoin
execute as @a[scores={shahed_rp=1..}] at @s run function shahed:rp/handle
execute as @a[scores={shahed_test=1..}] at @s run function shahed:rp/test_tick
"""
    wr("tick", t)

MSG_HEAD = '{"text":"♪ Шахед/ракета: ","color":"gold","bold":true}'
BTN = lambda txt, col, val, hint: f'{{"text":"{txt}","color":"{col}","clickEvent":{{"action":"run_command","value":"/trigger shahed_rp set {val}"}},"hoverEvent":{{"action":"show_text","contents":"{hint}"}}}}'
wr("rp/welcome", f"""
scoreboard players set @s shahed_seen 1
execute unless score @s shahed_mode matches 0.. run scoreboard players set @s shahed_mode 0
scoreboard players enable @s shahed_rp
function shahed:rp/prompt
""")
wr("rp/rejoin", """
scoreboard players set @s shahed_left 0
scoreboard players enable @s shahed_rp
execute unless score @s shahed_mode matches 1 run function shahed:rp/prompt
""")
wr("rp/prompt", f"""
tellraw @s ["",{MSG_HEAD},{{"text":"полные звуки (мопед шахеда, свист ракеты) — из пакета ресурсов ","color":"gray"}},{{"text":"Shahed Sounds","color":"yellow"}},{{"text":". Без него играют упрощённые звуки из модов.","color":"gray"}}]
tellraw @s ["",{{"text":"   Нажми ","color":"gray"}},{BTN("[▶ Проверить звук]","aqua",2,"Проиграет 2 секунды мотора шахеда и свиста ракеты")},{{"text":" и потом ","color":"gray"}},{BTN("[✔ Слышу]","green",1,"Включить полные звуки для меня")},{{"text":" или ","color":"gray"}},{BTN("[✘ Тишина]","red",3,"Оставить звуки из модов")}]
tellraw @s {{"text":"   Нет пакета? Возьми Shahed_Sounds.zip у хоста → положи в папку resourcepacks → Настройки → Пакеты ресурсов → включи.","color":"dark_gray"}}
""")
wr("rp/handle", """
execute if score @s shahed_rp matches 1 run function shahed:rp/on
execute if score @s shahed_rp matches 2 run function shahed:rp/test
execute if score @s shahed_rp matches 3 run function shahed:rp/off
scoreboard players set @s shahed_rp 0
scoreboard players enable @s shahed_rp
""")
wr("rp/on", """
scoreboard players set @s shahed_mode 1
tellraw @s {"text":"✔ Полные звуки включены. Проверить снова: /trigger shahed_rp set 2","color":"green"}
""")
wr("rp/off", """
scoreboard players set @s shahed_mode 0
tellraw @s {"text":"Ок, для тебя звучат звуки из модов. Когда поставишь пакет Shahed Sounds: /trigger shahed_rp set 2","color":"yellow"}
""")
wr("rp/test", """
scoreboard players set @s shahed_test 40
tellraw @s {"text":"Слушай: сначала мотор шахеда, затем свист ракеты…","color":"aqua"}
""")
wr("rp/test_tick", """
scoreboard players remove @s shahed_test 1
scoreboard players operation #m3 shahed = @s shahed_test
scoreboard players operation #m3 shahed %= #3 shahed
execute if score #m3 shahed matches 0 if score @s shahed_test matches 20.. run playsound shahed:drone.near ambient @s ^2 ^1 ^3 1 1 0
execute if score #m3 shahed matches 0 if score @s shahed_test matches ..19 run playsound shahed:missile.front.near ambient @s ^-2 ^1 ^3 1 1.3 0
execute if score @s shahed_test matches 0 run tellraw @s ["",{"text":"Было слышно? ","color":"gray"},""" + BTN("[✔ Слышу]","green",1,"Включить полные звуки") + ',{"text":" ","color":"gray"},' + BTN("[✘ Тишина]","red",3,"Оставить звуки из модов") + "]\n")

# ---------- звук: выбор по режиму слушателя ----------
t = rd("snd/listener")
if "play_custom" not in t:
    old_tail = t[t.index("execute if score #kind shahed matches 1 run function shahed:snd/pick_drone"):]
    t = t.replace(old_tail, """execute if score @s shahed_mode matches 1 run function shahed:snd/play_custom
execute unless score @s shahed_mode matches 1 run function shahed:snd/play_fallback
execute if score #kind shahed matches 2 if score #nodop shahed matches 0 if score #ld shahed matches ..320 unless score @s shahed_fbid = #sid shahed run function shahed:snd/missile_pass
""")
    wr("snd/listener", t)
wr("snd/play_custom", """
execute if score #kind shahed matches 1 run function shahed:snd/pick_drone
execute if score #kind shahed matches 2 run function shahed:snd/pick_missile
execute store result storage shahed:tmp snd.p double 0.01 run scoreboard players get #p shahed
execute store result storage shahed:tmp snd.g double 0.01 run scoreboard players get #g shahed
function shahed:snd/play with storage shahed:tmp snd
""")
wr("snd/play_fallback", """
# без пакета: те же направление, дальность и Доплер, но звуки из модов (раз в 6 тиков)
execute unless score #m6 shahed matches 0 run return 0
execute store result storage shahed:tmp snd.g double 0.01 run scoreboard players get #g shahed
execute if score #kind shahed matches 1 run function shahed:snd/fb_drone
execute if score #kind shahed matches 2 run function shahed:snd/fb_missile
""")
def fb(event, base):
    return f"""scoreboard players set #k shahed {base}
scoreboard players operation #fp shahed = #p shahed
scoreboard players operation #fp shahed *= #k shahed
scoreboard players operation #fp shahed /= #100 shahed
execute if score #fp shahed matches 201.. run scoreboard players set #fp shahed 200
execute store result storage shahed:tmp snd.p double 0.01 run scoreboard players get #fp shahed
data modify storage shahed:tmp snd.e set value "{event}"
function shahed:snd/play with storage shahed:tmp snd"""
wr("snd/fb_drone", fb("immersive_aircraft:propeller_tiny", 130) + "\n" + fb("petrochem:engine", 190))
wr("snd/fb_missile", fb("petrochem:turbine", 175))
# #m6 — чётность вызова для запасных звуков
t = rd("snd/source")
if "#m6" not in t:
    wr("snd/source", t.replace("scoreboard players set #nodop shahed 0\n",
        "scoreboard players set #nodop shahed 0\nscoreboard players operation #m6 shahed = @s shahed_t\nscoreboard players operation #m6 shahed %= #6 shahed\n"))
for n in ("snd/tail_drone", "snd/tail_missile"):
    t = rd(n)
    if "#m6" not in t:
        wr(n, t.replace("scoreboard players set #nodop shahed 1\n",
            "scoreboard players set #nodop shahed 1\nscoreboard players operation #m6 shahed = @s shahed_t\nscoreboard players operation #m6 shahed %= #6 shahed\n"))

# ---------- сирены и удар по режиму ----------
for n, a, b, tt in [("drone/siren", "~70 ~12 ~50", "~-85 ~12 ~-45", '{"text":"⚠ ВОЗДУШНАЯ ТРЕВОГА ⚠","color":"red","bold":true}'),
                    ("missile/siren", "~80 ~12 ~-40", "~-60 ~12 ~75", '{"text":"⚠ РАКЕТНАЯ ОПАСНОСТЬ ⚠","color":"red","bold":true}')]:
    wr(n, f"""
playsound shahed:siren master @a[scores={{shahed_mode=1}}] {a} 16 1 0
playsound shahed:siren master @a[scores={{shahed_mode=1}}] {b} 16 0.97 0
playsound snassets:signal/siren master @a[distance=..350,scores={{shahed_mode=0}}] ~ ~ ~ 0.3 1 0.3
title @a[distance=..350] actionbar {tt}
""")
for n in ("fx/arrive", "mfx/arrive"):
    t = rd(n)
    if "shahed_mode=1" not in t:
        t = t.replace("execute if score #band shahed matches", "execute if entity @s[scores={shahed_mode=1}] if score #band shahed matches", 2)
        wr(n, t)
h = rd("help")
if "shahed_rp" not in h:
    wr("help", h + '\ntellraw @s [{"text":"Звук: ","color":"gray"},{"text":"/trigger shahed_rp set 2","color":"aqua"},{"text":" — проверить пакет Shahed Sounds (у каждого игрока свой режим)","color":"gray"}]')
print("ok")
