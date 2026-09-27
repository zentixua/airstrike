# «Пульт» в чате: тип / сколько / разброс кнопками, огонь по себе / куда смотрю / по любому игроку.
# Плюс: выбор игрока кликом, если ник введён с ошибкой; залп по игроку в пещере; обратная связь по залпу.
import os, sys, json
DP = sys.argv[1]; FN = DP + "/data/shahed/function"
def rd(n): return open(f"{FN}/{n}.mcfunction", encoding="utf-8").read()
def wr(n, t):
    p = f"{FN}/{n}.mcfunction"; os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w", encoding="utf-8").write(t.strip("\n") + "\n")
def J(x): return json.dumps(x, ensure_ascii=False, separators=(",", ":"))
def btn(text, color, cmd, hover, bold=False):
    b = {"text": text, "color": color, "clickEvent": {"action": "run_command", "value": cmd}, "hoverEvent": {"action": "show_text", "contents": hover}}
    if bold: b["bold"] = True
    return b

os.makedirs(DP + "/data/shahed/loot_table", exist_ok=True)
json.dump({"pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "minecraft:player_head",
           "functions": [{"function": "minecraft:fill_player_head", "entity": "this"}]}]}]},
          open(DP + "/data/shahed/loot_table/player_head.json", "w"), indent=1)

t = rd("load")
if "shahed_mt" not in t:
    t = t.replace("scoreboard objectives add shahed dummy\n", "scoreboard objectives add shahed dummy\n" +
                  "".join(f"scoreboard objectives add {o} dummy\n" for o in ["shahed_mt", "shahed_mn", "shahed_mr", "shahed_own"]))
    wr("load", t)

# ---------- список игроков кликом (имя берём из головы игрока) ----------
wr("ui/players", """
# $(cmd) — функция, которой передаётся {name:"<ник>"}
tag @s add shahed_viewer
$data modify storage shahed:tmp pl.cmd set value "$(cmd)"
execute at @s run summon minecraft:armor_stand ~ ~-3 ~ {Tags:["shahed","shahed_namer"],Invisible:1b,Marker:1b,NoGravity:1b,Silent:1b}
execute as @a run function shahed:ui/player_btn
kill @e[type=minecraft:armor_stand,tag=shahed_namer]
tag @s remove shahed_viewer
""")
wr("ui/player_btn", """
data remove storage shahed:tmp pl.name
loot replace entity @e[type=minecraft:armor_stand,tag=shahed_namer,limit=1] armor.head loot shahed:player_head
data modify storage shahed:tmp pl.name set from entity @e[type=minecraft:armor_stand,tag=shahed_namer,limit=1] ArmorItems[3].components."minecraft:profile".name
execute if data storage shahed:tmp pl.name run function shahed:ui/player_btn_m with storage shahed:tmp pl
""")
wr("ui/player_btn_m", '$tellraw @a[tag=shahed_viewer] ["",{"text":"    ▶ ","color":"dark_gray"},{"text":"$(name)","color":"gold","underlined":true,"clickEvent":{"action":"run_command","value":"/function $(cmd) {name:\\"$(name)\\"}"},"hoverEvent":{"action":"show_text","contents":"Цель: $(name)"}}]')
wr("ui/notfound", """
$tellraw @s ["",{"text":"Игрок «$(name)» не найден (регистр букв важен). ","color":"red"},{"text":"Кликни по нику:","color":"gray"}]
$function shahed:ui/players {cmd:"$(cmd)"}
""")
for n, cmd in (("strike", "shahed:strike"), ("missile_strike", "shahed:missile_strike"), ("bunker_strike", "shahed:bunker_strike")):
    s = rd(n)
    import re
    s = re.sub(r'\$execute unless entity @a\[name=\$\(name\)\] run return run tellraw @s \{[^\n]*\}',
               f'$execute unless entity @a[name=$(name)] run return run function shahed:ui/notfound {{name:"$(name)",cmd:"{cmd}"}}', s)
    assert "ui/notfound" in s, n
    wr(n, s)

# ---------- залп: общее ядро ----------
wr("salvo/begin", """
scoreboard players set #st shahed 0
""" + "\n".join(f'execute if data storage shahed:tmp sv{{type:"{k}"}} run scoreboard players set #st shahed {v}' for k, v in
                [("drone",1),("shahed",1),("шахед",1),("missile",2),("ракета",2),("bunker",3),("бомба",3)]) + """
execute if score #st shahed matches 0 run return run tellraw @s {"text":"Тип залпа: drone, missile или bunker (шахед / ракета / бомба)","color":"red"}
function shahed:salvo/core
""")
wr("salvo/core", """
# контекст: позиция центра, @s — кто запускает; #st — тип; sv.count / sv.r — сколько и разброс
execute store result score #c shahed run data get storage shahed:tmp sv.count
execute if score #c shahed matches ..0 run scoreboard players set #c shahed 1
execute if score #c shahed matches 31.. run scoreboard players set #c shahed 30
execute store result storage shahed:tmp sv.count int 1 run scoreboard players get #c shahed
execute store result score #r shahed run data get storage shahed:tmp sv.r
execute if score #r shahed matches ..-1 run scoreboard players set #r shahed 0
execute if score #r shahed matches 151.. run scoreboard players set #r shahed 150
execute store result storage shahed:tmp sv.r int 1 run scoreboard players get #r shahed
data modify storage shahed:tmp sv.yaw set from entity @s Rotation[0]
scoreboard players add #next shahed_id 1
scoreboard players operation @s shahed_own = #next shahed_id
summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_salvo","shahed_newsalvo"]}
execute as @e[type=minecraft:marker,tag=shahed_newsalvo] run function shahed:salvo/init
execute if data storage shahed:cfg {siren:1b} unless score #st shahed matches 2 run function shahed:drone/siren
execute if data storage shahed:cfg {siren:1b} if score #st shahed matches 2 run function shahed:missile/siren
execute if score #st shahed matches 1 run tellraw @s ["",{"text":"✈ Залп шахедов: ","color":"red","bold":true},{"storage":"shahed:tmp","nbt":"sv.count","color":"yellow"},{"text":" шт., разброс ","color":"gray"},{"storage":"shahed:tmp","nbt":"sv.r","color":"yellow"},{"text":" бл. Подлёт первого ~5 с.","color":"gray"}]
execute if score #st shahed matches 2 run tellraw @s ["",{"text":"➶ Залп крылатых ракет: ","color":"red","bold":true},{"storage":"shahed:tmp","nbt":"sv.count","color":"yellow"},{"text":" шт., разброс ","color":"gray"},{"storage":"shahed:tmp","nbt":"sv.r","color":"yellow"},{"text":" бл. Первая ~7 с.","color":"gray"}]
execute if score #st shahed matches 3 run tellraw @s ["",{"text":"✹ Налёт B-2: ","color":"red","bold":true},{"storage":"shahed:tmp","nbt":"sv.count","color":"yellow"},{"text":" бомб, разброс ","color":"gray"},{"storage":"shahed:tmp","nbt":"sv.r","color":"yellow"},{"text":" бл. Первый заход ~3 с.","color":"gray"}]
""")
wr("salvo/init", """
tag @s remove shahed_newsalvo
scoreboard players operation @s shahed_id = #next shahed_id
scoreboard players operation @s shahed_ph = #st shahed
execute store result score @s shahed_E run data get storage shahed:tmp sv.count
scoreboard players operation @s shahed_trav = @s shahed_E
data modify entity @s data.r set from storage shahed:tmp sv.r
data modify entity @s data.yaw set from storage shahed:tmp sv.yaw
scoreboard players set @s shahed_t 1
""")
wr("salvo/tick", """
scoreboard players remove @s shahed_t 1
execute if score @s shahed_t matches 1.. run return 0
execute if score @s shahed_E matches ..0 run return run function shahed:salvo/done
function shahed:salvo/fire
scoreboard players remove @s shahed_E 1
execute if score @s shahed_ph matches 1 store result score @s shahed_t run random value 20..40
execute if score @s shahed_ph matches 2 store result score @s shahed_t run random value 15..30
execute if score @s shahed_ph matches 3 store result score @s shahed_t run random value 60..80
function shahed:salvo/hud
""")
wr("salvo/hud", """
# строка над хотбаром у того, кто запустил
scoreboard players operation #own shahed = @s shahed_id
scoreboard players operation #done shahed = @s shahed_trav
scoreboard players operation #done shahed -= @s shahed_E
execute store result storage shahed:tmp hud.d int 1 run scoreboard players get #done shahed
execute store result storage shahed:tmp hud.n int 1 run scoreboard players get @s shahed_trav
execute as @a if score @s shahed_own = #own shahed run title @s actionbar ["",{"text":"ПУСК ","color":"red","bold":true},{"storage":"shahed:tmp","nbt":"hud.d","color":"yellow"},{"text":" / ","color":"gray"},{"storage":"shahed:tmp","nbt":"hud.n","color":"yellow"}]
""")
wr("salvo/done", """
scoreboard players operation #own shahed = @s shahed_id
execute as @a if score @s shahed_own = #own shahed run title @s actionbar {"text":"Залп выпущен полностью","color":"gray"}
kill @s
""")
wr("salvo/fire", """
data modify storage shahed:tmp sv2.r set from entity @s data.r
scoreboard players set #try shahed 0
scoreboard players set #dx shahed 0
scoreboard players set #dz shahed 0
execute store result score #r shahed run data get entity @s data.r
execute if score #r shahed matches 1.. run function shahed:salvo/pick with storage shahed:tmp sv2
execute store result storage shahed:tmp sp2.x int 1 run scoreboard players get #dx shahed
execute store result storage shahed:tmp sp2.z int 1 run scoreboard players get #dz shahed
kill @e[type=minecraft:marker,tag=shahed_tgt_new]
# шахеды и ракеты бьют по поверхности; бомба — на глубине центра (найдёт пещеру под игроком)
execute unless score @s shahed_ph matches 3 run function shahed:salvo/place with storage shahed:tmp sp2
execute if score @s shahed_ph matches 3 run function shahed:salvo/place_deep with storage shahed:tmp sp2
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
wr("salvo/place_deep", '$execute positioned ~$(x) ~ ~$(z) run summon minecraft:marker ~ ~1 ~ {Tags:["shahed_tgt_new"]}')
wr("salvo", """
# /function shahed:salvo {type:"drone",count:6,radius:25} — залп вокруг того места, где ты стоишь
$data modify storage shahed:tmp sv set value {type:"$(type)",count:$(count),r:$(radius)}
execute at @s run function shahed:salvo/begin
""")
wr("salvo_look", """
# /function shahed:salvo_look {type:"missile",count:4,radius:30} — вокруг точки, куда смотришь
$data modify storage shahed:tmp sv set value {type:"$(type)",count:$(count),r:$(radius)}
function shahed:ray/start
execute unless entity @e[type=minecraft:marker,tag=shahed_tgt_new] run return run tellraw @s {"text":"Цель не найдена","color":"red"}
execute at @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] rotated as @s run function shahed:salvo/begin
kill @e[type=minecraft:marker,tag=shahed_tgt_new]
""")

# ---------- пульт ----------
TYPES = [(1, "✈ Шахед", "Барражирующий боеприпас: мопедный гул, пике, взрыв"), (2, "➶ Ракета", "Крылатая ракета: бреющий полёт, горка, свист, мощный взрыв"),
         (3, "✹ Бомба", "B-2 и бетонобойная бомба: пробивает грунт, взрыв под землёй")]
COUNTS = [1, 3, 5, 10, 20]
RADII = [(0, "точно"), (10, "10"), (25, "25"), (50, "50"), (100, "100")]
def row(label, items, sel, cmd, hover):
    out = []
    for val, txt, hv in items:
        on = val == sel
        out.append(btn(f"[{txt}]" if not on else f"[{txt}]", "yellow" if on else "gray", f"/function shahed:menu/{cmd} {{v:{val}}}", hv, bold=on))
        out.append({"text": " "})
    return ["", {"text": label, "color": "dark_aqua"}] + out
lines = []
lines.append("execute unless score @s shahed_mt matches 1..3 run scoreboard players set @s shahed_mt 1")
lines.append("execute unless score @s shahed_mn matches 1 unless score @s shahed_mn matches 3 unless score @s shahed_mn matches 5 unless score @s shahed_mn matches 10 unless score @s shahed_mn matches 20 run scoreboard players set @s shahed_mn 3")
lines.append("execute unless score @s shahed_mr matches 0 unless score @s shahed_mr matches 10 unless score @s shahed_mr matches 25 unless score @s shahed_mr matches 50 unless score @s shahed_mr matches 100 run scoreboard players set @s shahed_mr 25")
lines.append('tellraw @s ["",{"text":"━━━━━━━━  ☢ ПУЛЬТ УДАРОВ  ━━━━━━━━","color":"dark_red","bold":true}]')
for sel, _, _ in TYPES:
    lines.append(f"execute if score @s shahed_mt matches {sel} run tellraw @s " + J(row(" Оружие:  ", TYPES, sel, "t", "")))
for sel in COUNTS:
    lines.append(f"execute if score @s shahed_mn matches {sel} run tellraw @s " + J(row(" Сколько: ", [(c, str(c), f"{c} шт.") for c in COUNTS], sel, "n", "")))
for sel, _ in RADII:
    lines.append(f"execute if score @s shahed_mr matches {sel} run tellraw @s " + J(row(" Разброс: ", [(r, t, "точно в цель" if r == 0 else f"радиус {r} блоков") for r, t in RADII], sel, "r", "")))
lines.append("tellraw @s " + J(["", {"text": " Огонь:    ", "color": "dark_aqua"},
    btn("[⌖ ВОКРУГ МЕНЯ]", "red", "/function shahed:menu/fire_me", "Центр — там, где ты стоишь сейчас", True), {"text": "  "},
    btn("[◉ КУДА СМОТРЮ]", "red", "/function shahed:menu/fire_look", "Центр — точка, куда ты смотришь (до 200 блоков)", True)]))
lines.append('tellraw @s {"text":" По игроку:","color":"dark_aqua"}')
lines.append('function shahed:ui/players {cmd:"shahed:menu/fire_at"}')
lines.append("tellraw @s " + J(["", {"text": " ", "color": "gray"},
    btn("[✖ Отбой — убрать всё]", "dark_gray", "/function shahed:clear", "Убрать все летящие снаряды и залпы без взрыва"), {"text": "  "},
    btn("[? Настройки]", "dark_gray", "/function shahed:help", "Справка и параметры")]))
wr("menu/render", "\n".join(lines))
wr("menu", """
# /function shahed:menu — пульт в чате
execute unless score @s shahed_mt matches 1..3 run scoreboard players set @s shahed_mt 1
execute unless score @s shahed_mn matches 1.. run scoreboard players set @s shahed_mn 3
execute unless score @s shahed_mr matches 0.. run scoreboard players set @s shahed_mr 25
function shahed:menu/render
""")
wr("pult", "function shahed:menu")
for k, obj in (("t", "shahed_mt"), ("n", "shahed_mn"), ("r", "shahed_mr")):
    wr(f"menu/{k}", f"$scoreboard players set @s {obj} $(v)\nplaysound minecraft:ui.button.click master @s ~ ~ ~ 0.6 1.4\nfunction shahed:menu/render")
wr("menu/prep", """
scoreboard players operation #st shahed = @s shahed_mt
execute store result storage shahed:tmp sv.count int 1 run scoreboard players get @s shahed_mn
execute store result storage shahed:tmp sv.r int 1 run scoreboard players get @s shahed_mr
""")
wr("menu/fire_me", "function shahed:menu/prep\nexecute at @s run function shahed:salvo/core\nplaysound minecraft:block.note_block.bass master @s ~ ~ ~ 1 0.5")
wr("menu/fire_look", """
function shahed:menu/prep
function shahed:ray/start
execute unless entity @e[type=minecraft:marker,tag=shahed_tgt_new] run return run tellraw @s {"text":"Цель не найдена","color":"red"}
execute at @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] rotated as @s run function shahed:salvo/core
kill @e[type=minecraft:marker,tag=shahed_tgt_new]
playsound minecraft:block.note_block.bass master @s ~ ~ ~ 1 0.5
""")
wr("menu/fire_at", """
$execute unless entity @a[name=$(name)] run return run function shahed:ui/notfound {name:"$(name)",cmd:"shahed:menu/fire_at"}
function shahed:menu/prep
$execute at @a[name=$(name),limit=1] rotated as @s run function shahed:salvo/core
$tellraw @s ["",{"text":"   Цель: ","color":"gray"},{"text":"$(name)","color":"gold"}]
playsound minecraft:block.note_block.bass master @s ~ ~ ~ 1 0.5
""")

h = rd("help")
if "shahed:menu" not in h:
    h = h.replace('tellraw @s {"text":"=== Шахед ===","color":"gold","bold":true}',
                  'tellraw @s {"text":"=== Шахед ===","color":"gold","bold":true}\n' +
                  'tellraw @s [{"text":"/function shahed:menu","color":"yellow","bold":true,"clickEvent":{"action":"run_command","value":"/function shahed:menu"}},{"text":" — ПУЛЬТ: оружие, количество, разброс и цель кнопками","color":"gray"}]')
    wr("help", h)
print("ok")
