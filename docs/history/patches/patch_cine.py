# Патч «кино»: крупнее и светлее модели, звуковой движок (Доплер, дальность, направление), сирена, брызги грунта.
import os, re, sys
FN = sys.argv[1]  # .../datapacks/shahed/data/shahed/function
def rd(n): return open(f"{FN}/{n}.mcfunction", encoding="utf-8").read()
def wr(n, t):
    p = f"{FN}/{n}.mcfunction"; os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w", encoding="utf-8").write(t.strip("\n") + "\n")
def sub(n, a, b, count=1):
    t = rd(n)
    if a not in t:
        if b in t: return
        raise SystemExit(f"не найдено в {n}: {a!r}")
    wr(n, t.replace(a, b, count))

# ---------- 1. модели: масштаб и подсветка ----------
def scale_build(n, k):
    t = rd(n)
    if t.startswith("# scaled"): return
    def mul(m):
        vals = [float(v.rstrip('f')) * k for v in m.group(2).split(',')]
        return f"{m.group(1)}:[" + ",".join(f"{round(v,4):g}f" for v in vals) + "]"
    out = []
    for line in t.splitlines():
        if line.startswith("summon minecraft:block_display") or line.startswith("summon minecraft:item_display"):
            line = re.sub(r"(translation|scale):\[([^\]]+)\]", mul, line)
            if "brightness" not in line:
                line = line.replace("view_range:4f", "view_range:4f,brightness:{sky:15,block:11}")
        out.append(line)
    wr(n, f"# scaled x{k}\n" + "\n".join(out))
scale_build("drone/build", 1.35)
scale_build("missile/build", 1.25)
b = rd("missile/build")
if "shahed_lamp" not in b:
    b = b.replace("scoreboard players operation @e[tag=shahed_newpart] shahed_id = @s shahed_id",
                  'summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_part","shahed_newpart","shahed_lamp"]}\nscoreboard players operation @e[tag=shahed_newpart] shahed_id = @s shahed_id')
    wr("missile/build", b)

sub("drone/steer", "scoreboard players add #reach shahed 33\n", "scoreboard players add #reach shahed 43\n")
sub("drone/steer", "scoreboard players set #nose shahed 270\n", "scoreboard players set #nose shahed 365\n")
sub("missile/steer", "scoreboard players add #reach shahed 53\n", "scoreboard players add #reach shahed 65\n")
sub("missile/steer", "scoreboard players set #nose shahed 470\n", "scoreboard players set #nose shahed 588\n")
sub("drone/init", "scoreboard players set @s shahed_v 230\n", "scoreboard players set @s shahed_v 210\n")
sub("drone/dive_cmd", "execute if score @s shahed_v matches 331.. run scoreboard players set @s shahed_v 330",
    "execute if score @s shahed_v matches 301.. run scoreboard players set @s shahed_v 300")
t = rd("missile/steer")
t = t.replace("# свист за 150 блоков до цели\n", "").replace(
    "execute if score @s shahed_w matches 0 if score #d shahed matches ..1500 run scoreboard players set @s shahed_w 1\n", "").replace(
    "execute if score @s shahed_w matches 1.. run function shahed:missile/whistle_prep\n", "")
wr("missile/steer", t)

# ---------- 2. load: новые объективы ----------
t = rd("load")
if "shahed_sid" not in t:
    t = t.replace("scoreboard objectives add shahed dummy\n", "scoreboard objectives add shahed dummy\nscoreboard objectives add shahed_sid dummy\nscoreboard objectives add shahed_pd dummy\n")
    if "scoreboard players set #3 shahed 3" not in t:
        t = t.replace("scoreboard players set #2 shahed 2\n", "scoreboard players set #2 shahed 2\nscoreboard players set #3 shahed 3\n")
    wr("load", t)

# ---------- 3. полёт: след и звук ----------
wr("drone/visual", """
scoreboard players operation #cur shahed_id = @s shahed_id
execute as @e[tag=shahed_part] if score @s shahed_id = #cur shahed_id run tp @s ~ ~ ~ ~ ~
# сизый выхлоп двухтактника
particle minecraft:smoke ^ ^0.05 ^-3.8 0.05 0.05 0.05 0.01 2 force @a
execute if score @s shahed_ph matches 1 run particle minecraft:smoke ^ ^0.05 ^-3.8 0.08 0.08 0.08 0.02 3 force @a
# звук мотора — раз в 3 тика каждому слушателю (направление, дальность, Доплер)
scoreboard players operation #m3 shahed = @s shahed_t
scoreboard players operation #m3 shahed %= #3 shahed
execute if score #m3 shahed matches 0 run function shahed:snd/source
""")
wr("missile/visual", """
scoreboard players operation #cur shahed_id = @s shahed_id
# «живой» свет от двигателя: освещает землю под ракетой ночью
execute as @e[type=minecraft:marker,tag=shahed_lamp] if score @s shahed_id = #cur shahed_id at @s if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute as @e[tag=shahed_part] if score @s shahed_id = #cur shahed_id run tp @s ~ ~ ~ ~ ~
execute as @e[type=minecraft:marker,tag=shahed_lamp] if score @s shahed_id = #cur shahed_id at @s if block ~ ~ ~ #minecraft:air run setblock ~ ~ ~ minecraft:light[level=14]
particle minecraft:flame ^ ^ ^-6.1 0.06 0.06 0.06 0.01 4 force @a
particle minecraft:small_flame ^ ^ ^-6.3 0.05 0.05 0.05 0.01 3 force @a
particle minecraft:smoke ^ ^ ^-6.6 0.12 0.12 0.12 0.01 4 force @a
particle minecraft:cloud ^ ^ ^-7.4 0.04 0.04 0.04 0.003 1 force @a
execute if score @s shahed_ph matches 1.. run particle minecraft:firework ^ ^ ^-6.3 0.05 0.05 0.05 0.03 4 force @a
execute if score @s shahed_ph matches 2 run particle minecraft:white_smoke ^ ^ ^-2 1.4 0.1 1.4 0.01 4 force @a
scoreboard players operation #m3 shahed = @s shahed_t
scoreboard players operation #m3 shahed %= #3 shahed
execute if score #m3 shahed matches 0 run function shahed:snd/source
""")
wr("drone/detonate", """
scoreboard players operation #cur shahed_id = @s shahed_id
execute as @e[tag=shahed_part] if score @s shahed_id = #cur shahed_id run kill @s
summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_fx","shahed_newfx"]}
execute as @e[type=minecraft:marker,tag=shahed_newfx] at @s run function shahed:fx/start
kill @s
return 1
""")
wr("missile/detonate", """
scoreboard players operation #cur shahed_id = @s shahed_id
execute as @e[type=minecraft:marker,tag=shahed_lamp] if score @s shahed_id = #cur shahed_id at @s if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute as @e[tag=shahed_part] if score @s shahed_id = #cur shahed_id run kill @s
summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_mfx","shahed_newfx"]}
execute as @e[type=minecraft:marker,tag=shahed_newfx] at @s run function shahed:mfx/start
kill @s
return 1
""")
wr("drone/gc", """
scoreboard players set #clk shahed 0
tag @e[tag=shahed_part] add shahed_orphan
execute as @e[type=minecraft:marker,tag=shahed_root] run function shahed:drone/claim
execute as @e[type=minecraft:marker,tag=shahed_lamp,tag=shahed_orphan] at @s if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
kill @e[tag=shahed_orphan]
""")
wr("clear", """
execute as @e[type=minecraft:marker,tag=shahed_lamp] at @s if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
kill @e[tag=shahed]
kill @e[type=minecraft:marker,tag=shahed_helper]
stopsound @a ambient
stopsound @a master shahed:siren
effect clear @a[tag=shahed_nv] minecraft:night_vision
tag @a remove shahed_nv
scoreboard players reset * shahed_shake
tellraw @s {"text":"Все шахеды и ракеты убраны.","color":"gray"}
""")

# ---------- 4. звуковой движок ----------
SRC = """
execute store result score #sx shahed run data get entity @s Pos[0] 10
execute store result score #sy shahed run data get entity @s Pos[1] 10
execute store result score #sz shahed run data get entity @s Pos[2] 10
"""
wr("snd/source", "# источник звука — летящий шахед или ракета\ntag @s add shahed_src" + SRC + """
scoreboard players operation #sid shahed = @s shahed_id
scoreboard players set #kind shahed 1
execute if entity @s[tag=shahed_missile] run scoreboard players set #kind shahed 2
scoreboard players operation #sph shahed = @s shahed_ph
scoreboard players set #nodop shahed 0
execute as @a[distance=..320] at @s run function shahed:snd/listener
tag @s remove shahed_src
""")
for kind, sph, name in [(1, 1, "tail_drone"), (2, 2, "tail_missile")]:
    wr(f"snd/{name}", f"# звук ещё «летит» к дальним слушателям, пока до них не дошёл фронт взрыва\ntag @s add shahed_src" + SRC + f"""
scoreboard players set #sid shahed -1
scoreboard players set #kind shahed {kind}
scoreboard players set #sph shahed {sph}
scoreboard players set #nodop shahed 1
function shahed:snd/tail_run with storage shahed:tmp band
tag @s remove shahed_src
""")
wr("snd/tail_run", "$execute as @a[distance=$(b)..320] at @s run function shahed:snd/listener")
newton = "\n".join(["scoreboard players operation #q shahed = #d2 shahed\nscoreboard players operation #q shahed /= #s shahed\nscoreboard players operation #s shahed += #q shahed\nscoreboard players operation #s shahed /= #2 shahed\nexecute if score #s shahed matches ..0 run scoreboard players set #s shahed 1"] * 8)
wr("snd/listener", """
# расстояние от уха слушателя до источника (дециблоки)
execute store result score #lx shahed run data get entity @s Pos[0] 10
execute store result score #ly shahed run data get entity @s Pos[1] 10
scoreboard players add #ly shahed 16
execute store result score #lz shahed run data get entity @s Pos[2] 10
scoreboard players operation #ex shahed = #sx shahed
scoreboard players operation #ex shahed -= #lx shahed
scoreboard players operation #ey shahed = #sy shahed
scoreboard players operation #ey shahed -= #ly shahed
scoreboard players operation #ez shahed = #sz shahed
scoreboard players operation #ez shahed -= #lz shahed
scoreboard players operation #d2 shahed = #ex shahed
scoreboard players operation #d2 shahed *= #ex shahed
scoreboard players operation #q shahed = #ey shahed
scoreboard players operation #q shahed *= #ey shahed
scoreboard players operation #d2 shahed += #q shahed
scoreboard players operation #q shahed = #ez shahed
scoreboard players operation #q shahed *= #ez shahed
scoreboard players operation #d2 shahed += #q shahed
scoreboard players operation #s shahed = #ex shahed
execute if score #s shahed matches ..-1 run scoreboard players operation #s shahed *= #-1 shahed
scoreboard players operation #q shahed = #ey shahed
execute if score #q shahed matches ..-1 run scoreboard players operation #q shahed *= #-1 shahed
scoreboard players operation #s shahed += #q shahed
scoreboard players operation #q shahed = #ez shahed
execute if score #q shahed matches ..-1 run scoreboard players operation #q shahed *= #-1 shahed
scoreboard players operation #s shahed += #q shahed
execute if score #s shahed matches ..0 run scoreboard players set #s shahed 1
""" + newton + """
scoreboard players operation #ld shahed = #s shahed
# Доплер: скорость сближения за 3 тика
execute if score #nodop shahed matches 1 run scoreboard players operation @s shahed_pd = #ld shahed
execute unless score @s shahed_sid = #sid shahed run scoreboard players operation @s shahed_pd = #ld shahed
scoreboard players operation @s shahed_sid = #sid shahed
scoreboard players operation #vr shahed = #ld shahed
scoreboard players operation #vr shahed -= @s shahed_pd
scoreboard players operation @s shahed_pd = #ld shahed
# шахед: 150 км/ч при скорости звука 343 м/с (±15%); ракета: ~0.65 Маха (до ×2 на подлёте, ×0.6 после)
execute if score #kind shahed matches 1 run scoreboard players set #C shahed 515
execute if score #kind shahed matches 2 run scoreboard players set #C shahed 240
scoreboard players set #p shahed 100
execute if score #kind shahed matches 1 if score #sph shahed matches 1 run scoreboard players set #p shahed 112
scoreboard players operation #den shahed = #C shahed
scoreboard players operation #den shahed += #vr shahed
execute if score #den shahed matches ..60 run scoreboard players set #den shahed 60
scoreboard players operation #p shahed *= #C shahed
scoreboard players operation #p shahed /= #den shahed
execute if score #p shahed matches ..49 run scoreboard players set #p shahed 50
execute if score #p shahed matches 201.. run scoreboard players set #p shahed 200
# громкость ~ 1/расстояние: шахед на полную с 30 блоков, ракета с 45
execute if score #ld shahed matches ..0 run scoreboard players set #ld shahed 1
execute if score #kind shahed matches 1 run scoreboard players set #g shahed 30000
execute if score #kind shahed matches 2 run scoreboard players set #g shahed 45000
scoreboard players operation #g shahed /= #ld shahed
execute if score #g shahed matches ..5 run scoreboard players set #g shahed 6
execute if score #g shahed matches 101.. run scoreboard players set #g shahed 100
# тембр по дальности: рядом — полный, дальше воздух «съедает» верха
scoreboard players set #var shahed 0
execute if score #ld shahed matches 450.. run scoreboard players set #var shahed 1
execute if score #ld shahed matches 1300.. run scoreboard players set #var shahed 2
execute if score #kind shahed matches 1 run function shahed:snd/pick_drone
execute if score #kind shahed matches 2 run function shahed:snd/pick_missile
execute store result storage shahed:tmp snd.p double 0.01 run scoreboard players get #p shahed
execute store result storage shahed:tmp snd.g double 0.01 run scoreboard players get #g shahed
function shahed:snd/play with storage shahed:tmp snd
execute if score #kind shahed matches 2 if score #nodop shahed matches 0 if score #ld shahed matches ..320 unless score @s shahed_fbid = #sid shahed run function shahed:snd/missile_pass
""")
wr("snd/play", "$execute facing entity @e[type=minecraft:marker,tag=shahed_src,limit=1] feet positioned ^ ^ ^3 run playsound $(e) ambient @s ~ ~ ~ $(g) $(p) 0")
D = ["near", "mid", "far"]
wr("snd/pick_drone", "\n".join(f'execute if score #var shahed matches {i} run data modify storage shahed:tmp snd.e set value "shahed:drone.{d}"' for i, d in enumerate(D)))
lines = ["scoreboard players set #appr shahed 0",
         "execute if score #vr shahed matches ..-1 run scoreboard players set #appr shahed 1",
         "execute if score #nodop shahed matches 1 run scoreboard players set #appr shahed 1",
         "# спереди — свист вентилятора, сзади — рёв струи, в пике — пронзительный свист"]
for i, d in enumerate(D):
    lines.append(f'execute if score #appr shahed matches 0 if score #var shahed matches {i} run data modify storage shahed:tmp snd.e set value "shahed:missile.rear.{d}"')
    lines.append(f'execute if score #appr shahed matches 1 unless score #sph shahed matches 2 if score #var shahed matches {i} run data modify storage shahed:tmp snd.e set value "shahed:missile.front.{d}"')
    lines.append(f'execute if score #appr shahed matches 1 if score #sph shahed matches 2 if score #var shahed matches {i} run data modify storage shahed:tmp snd.e set value "shahed:missile.dive.{d}"')
wr("snd/pick_missile", "\n".join(lines))
wr("snd/missile_pass", """
scoreboard players operation @s shahed_fbid = #sid shahed
playsound snassets:120_flyby master @s ~ ~ ~ 1 0.8 1
playsound snassets:weapons/basic_ocp_rocket master @s ~ ~ ~ 1 0.75 1
""")

# хвост звука после взрыва + остановка мотора, когда дошёл грохот
t = rd("fx/tick")
if "snd/tail_drone" not in t:
    t = t.replace("execute if score @s shahed_t matches 1..24 run function shahed:fx/front\n",
        "execute if score @s shahed_t matches 1..24 run function shahed:fx/front\nscoreboard players operation #m3 shahed = @s shahed_t\nscoreboard players operation #m3 shahed %= #3 shahed\nexecute if score @s shahed_t matches 1..24 if score #m3 shahed matches 0 run function shahed:snd/tail_drone\n")
    wr("fx/tick", t)
t = rd("mfx/tick")
if "snd/tail_missile" not in t:
    t = t.replace("execute if score @s shahed_t matches 1..30 run function shahed:mfx/front\n",
        "execute if score @s shahed_t matches 1..30 run function shahed:mfx/front\nscoreboard players operation #m3 shahed = @s shahed_t\nscoreboard players operation #m3 shahed %= #3 shahed\nexecute if score @s shahed_t matches 1..30 if score #m3 shahed matches 0 run function shahed:snd/tail_missile\n")
    wr("mfx/tick", t)
for n, sub_b, far_b, pitch in [("fx/arrive", "1..6", "4..", "1"), ("mfx/arrive", "1..8", "4..", "0.85")]:
    t = rd(n)
    if "blast.sub" not in t:
        wr(n, f"""
# пришёл фронт взрыва: мотор/свист обрывается, бьёт по груди
stopsound @s ambient
execute if score #band shahed matches {sub_b} run playsound shahed:blast.sub master @s ~ ~ ~ 1 {pitch} 1
execute if score #band shahed matches {far_b} run playsound shahed:blast.far master @s ~ ~ ~ 1 {pitch} 1
""" + t)

# ---------- 5. сирены: две далёкие городские, а не в ухо ----------
wr("drone/siren", """
playsound shahed:siren master @a ~70 ~12 ~50 16 1 0
playsound shahed:siren master @a ~-85 ~12 ~-45 16 0.97 0
title @a[distance=..300] actionbar {"text":"⚠ ВОЗДУШНАЯ ТРЕВОГА ⚠","color":"red","bold":true}
""")
wr("missile/siren", """
playsound shahed:siren master @a ~80 ~12 ~-40 16 1 0
playsound shahed:siren master @a ~-60 ~12 ~75 16 1.03 0
title @a[distance=..350] actionbar {"text":"⚠ РАКЕТНАЯ ОПАСНОСТЬ ⚠","color":"red","bold":true}
""")

# ---------- 6. выброс грунта и пыль цвета местности ----------
MAT = {1:("minecraft:dirt","0.36","0.27","0.18"), 2:("minecraft:stone","0.5","0.5","0.5"), 3:("minecraft:deepslate","0.3","0.3","0.32"),
       4:("minecraft:sand","0.85","0.78","0.58"), 5:("minecraft:snow_block","0.95","0.95","0.97"), 6:("minecraft:oak_planks","0.45","0.33","0.2"),
       7:("minecraft:stone_bricks","0.55","0.52","0.5"), 8:("minecraft:terracotta","0.6","0.38","0.28"), 9:("minecraft:gravel","0.45","0.42","0.38"),
       10:("minecraft:gravel","0.8","0.85","0.9")}
wr("fx/spray_pick", "\n".join(
    f'execute if score @s shahed_mat matches {k} run data modify entity @s data.sp set value {{b:"{b}",r:{r},g:{g},bl:{bl}}}' for k,(b,r,g,bl) in MAT.items()))
for kind, n1, n2, spd in [("fx", 350, 150, 0.9), ("mfx", 900, 400, 1.3)]:
    wr(f"{kind}/spray", f"""
function shahed:fx/spray_pick
function shahed:{kind}/spray_m with entity @s data.sp
execute if score @s shahed_mat matches 10 run particle minecraft:splash ~ ~1 ~ 3 3 3 0.8 {n1*2} force @a
execute if score @s shahed_mat matches 10 run particle minecraft:bubble_pop ~ ~1 ~ 4 2 4 0.3 {n2} force @a
""")
    wr(f"{kind}/spray_m", f"""
# фонтан грунта из воронки и пылевое облако цвета местности
$particle minecraft:block{{block_state:{{Name:"$(b)"}}}} ~ ~1 ~ 1.5 1.5 1.5 {spd} {n1} force @a
$particle minecraft:block{{block_state:{{Name:"$(b)"}}}} ~ ~3 ~ 4 3 4 0.4 {n2} force @a
$particle minecraft:dust{{color:[$(r)f,$(g)f,$(bl)f],scale:4.0f}} ~ ~1.5 ~ 5 1.2 5 0.02 {n2} force @a
""")
    wr(f"{kind}/dust_m", """
$particle minecraft:dust{color:[$(r)f,$(g)f,$(bl)f],scale:4.0f} ~ ~1 ~ 9 1 9 0.01 25 force @a
""")
    t = rd(f"{kind}/t1")
    if "spray" not in t: wr(f"{kind}/t1", f"function shahed:{kind}/spray\n" + t)
    t = rd(f"{kind}/smoke")
    if "dust_m" not in t: wr(f"{kind}/smoke", t + f"\nexecute if score @s shahed_t matches ..90 run function shahed:{kind}/dust_m with entity @s data.sp")
print("ok")
