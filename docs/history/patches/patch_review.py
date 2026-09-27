import os, re, sys, json
D = sys.argv[1]
F = os.path.join(D, "data/shahed/function")
def P(p): return os.path.join(F, p + ".mcfunction")
def rd(p): return open(P(p), encoding="utf-8").read()
def wr(p, s):
    os.makedirs(os.path.dirname(P(p)), exist_ok=True)
    if not s.endswith("\n"): s += "\n"
    open(P(p), "w", encoding="utf-8").write(s)
def sub(p, old, new, count=1):
    s = rd(p)
    n = s.count(old)
    if n == 0:
        raise SystemExit(f"[{p}] not found: {old[:90]!r}")
    if count == 1 and n > 1:
        raise SystemExit(f"[{p}] ambiguous ({n}): {old[:90]!r}")
    s = s.replace(old, new) if count != 1 else s.replace(old, new, 1)
    wr(p, s)
def subre(p, pat, new, n_expected=None):
    s = rd(p)
    s2, n = re.subn(pat, new, s, flags=re.M)
    if n == 0 or (n_expected and n != n_expected):
        raise SystemExit(f"[{p}] regex {pat!r} matched {n}")
    wr(p, s2)

# ---------- 1. tick: gate + player cache ----------
wr("tick", """# игроки: подсказки про звук, тряска камеры — всегда
execute as @a unless score @s shahed_seen matches 1 run function shahed:rp/welcome
execute as @a[scores={shahed_left=1..}] run function shahed:rp/rejoin
execute as @a[scores={shahed_rp=1..}] at @s run function shahed:rp/handle
execute as @a[scores={shahed_test=1..}] at @s run function shahed:rp/test_tick
execute as @a[scores={shahed_shake=1..}] at @s run function shahed:fx/shake
execute as @a[scores={shahed_quake=1..}] at @s run function shahed:bfx/quake

# ничего нашего в мире нет (кроме служебных маркеров) — дальше не считаем
execute unless entity @e[tag=shahed,tag=!shahed_helper,tag=!shahed_helper2] run return 0

# позиции игроков — один раз за тик, пока что-то летит или звучит (звук и слежение берут отсюда)
execute if entity @e[type=minecraft:marker,tag=shahed_snd] as @a run function shahed:snd/cache

execute as @e[type=minecraft:marker,tag=shahed_root,tag=!shahed_missile,tag=!shahed_bunker,tag=!shahed_bomber] at @s run function shahed:drone/tick
execute as @e[type=minecraft:marker,tag=shahed_root,tag=shahed_missile] at @s run function shahed:missile/tick
execute as @e[type=minecraft:marker,tag=shahed_root,tag=shahed_bomber] at @s run function shahed:bunker/bomber_tick
execute as @e[type=minecraft:marker,tag=shahed_root,tag=shahed_bunker] at @s run function shahed:bunker/tick
execute as @e[type=minecraft:marker,tag=shahed_fx] at @s run function shahed:fx/tick
execute as @e[type=minecraft:marker,tag=shahed_mfx] at @s run function shahed:mfx/tick
execute as @e[type=minecraft:marker,tag=shahed_bfx] at @s run function shahed:bfx/tick
execute as @e[type=minecraft:marker,tag=shahed_vent_on] at @s run function shahed:bfx/vent_tick
execute as @e[type=minecraft:marker,tag=shahed_surf] at @s run function shahed:bfx/surf_tick
execute as @e[type=minecraft:falling_block,tag=shahed_debris] at @s run function shahed:fx/debris_trail
execute as @e[type=minecraft:marker,tag=shahed_salvo] at @s run function shahed:salvo/tick

scoreboard players add #clk shahed 1
execute if score #clk shahed matches 20.. run function shahed:drone/gc
""")
wr("snd/cache", """# позиция игрока (дециблоки) — одно чтение NBT за тик вместо десятков
data modify storage shahed:tmp pp set from entity @s Pos
execute store result score @s shahed_px run data get storage shahed:tmp pp[0] 10
execute store result score @s shahed_py run data get storage shahed:tmp pp[1] 10
execute store result score @s shahed_pz run data get storage shahed:tmp pp[2] 10
""")

# shahed_snd tag on everything that makes flight sound
for p, old in [("drone/spawn", 'Tags:["shahed","shahed_root","shahed_new"]'),
               ("missile/spawn", 'Tags:["shahed","shahed_root","shahed_missile","shahed_new"]'),
               ("bunker/bomb_spawn", 'Tags:["shahed","shahed_root","shahed_bunker","shahed_new"]'),
               ("bunker/bomber_spawn", 'Tags:["shahed","shahed_root","shahed_bomber","shahed_new"]')]:
    sub(p, old, old.replace('"shahed_new"', '"shahed_snd","shahed_new"'))
    sub(p, "tag=shahed_new,limit=1]", "tag=shahed_new,limit=1,sort=nearest]")
sub("drone/detonate", 'Tags:["shahed","shahed_fx","shahed_newfx"]', 'Tags:["shahed","shahed_fx","shahed_snd","shahed_newfx"]')
sub("missile/detonate", 'Tags:["shahed","shahed_mfx","shahed_newfx"]', 'Tags:["shahed","shahed_mfx","shahed_snd","shahed_newfx"]')

# ---------- guards in ticks ----------
for p in ["drone/tick", "missile/tick", "bunker/tick", "bunker/bomber_tick"]:
    s = rd(p)
    assert s.startswith("scoreboard players add @s shahed_t 1\n"), p
    wr(p, "# маркер без инициализации (сбой спавна) — убрать, чтобы не сыпал ошибками\nexecute unless score @s shahed_id matches 1.. run return run kill @s\n" + s)

# ---------- fx / mfx: tails only while band < 320, lights, lightning ----------
sub("fx/tick", "execute if score @s shahed_t matches 1..24 if score #m3 shahed matches 0 run function shahed:snd/tail_drone",
    "execute if score @s shahed_t matches 1..18 if score #m3 shahed matches 0 run function shahed:snd/tail_drone\nexecute if score @s shahed_t matches 19 run tag @s remove shahed_snd")
sub("mfx/tick", "execute if score @s shahed_t matches 1..30 if score #m3 shahed matches 0 run function shahed:snd/tail_missile",
    "execute if score @s shahed_t matches 1..18 if score #m3 shahed matches 0 run function shahed:snd/tail_missile\nexecute if score @s shahed_t matches 19 run tag @s remove shahed_snd")
sub("mfx/start", "summon minecraft:lightning_bolt ~ ~16 ~",
    "particle minecraft:flash ~ ~14 ~ 0 0 0 0 1 force @a\nparticle minecraft:flash ~ ~6 ~ 3 3 3 0 4 force @a")
sub("mfx/t3", "summon minecraft:lightning_bolt ~4 ~20 ~-3", "particle minecraft:flash ~4 ~18 ~-3 0 0 0 0 1 force @a")
sub("mfx/t9", "summon minecraft:lightning_bolt ~-3 ~22 ~4", "particle minecraft:flash ~-3 ~20 ~4 0 0 0 0 1 force @a")

# ---------- cfg clamp ----------
wr("cfg_clamp", """# защита от опечаток в настройках: слишком большой взрыв подвесит сервер
execute store result score #cp shahed run data get storage shahed:cfg power
execute if score #cp shahed matches ..0 run data modify storage shahed:cfg power set value 12
execute if score #cp shahed matches 61.. run data modify storage shahed:cfg power set value 60
execute store result score #cp shahed run data get storage shahed:cfg missile_power
execute if score #cp shahed matches ..0 run data modify storage shahed:cfg missile_power set value 20
execute if score #cp shahed matches 61.. run data modify storage shahed:cfg missile_power set value 60
execute store result score #cp shahed run data get storage shahed:cfg bunker_power
execute if score #cp shahed matches ..0 run data modify storage shahed:cfg bunker_power set value 20
execute if score #cp shahed matches 61.. run data modify storage shahed:cfg bunker_power set value 60
""")
for p, line in [("fx/start", "function shahed:fx/main_blast with storage shahed:cfg"),
                ("mfx/start", "function shahed:mfx/main_blast with storage shahed:cfg"),
                ("bfx/start", "function shahed:bfx/main_blast with storage shahed:cfg")]:
    sub(p, line, "function shahed:cfg_clamp\n" + line)

# ---------- sound engine ----------
s = rd("snd/listener")
head_old = """execute store result score #lx shahed run data get entity @s Pos[0] 10
execute store result score #ly shahed run data get entity @s Pos[1] 10
scoreboard players add #ly shahed 16
execute store result score #lz shahed run data get entity @s Pos[2] 10
"""
assert head_old in s
s = s.replace(head_old, """scoreboard players operation #lx shahed = @s shahed_px
scoreboard players operation #ly shahed = @s shahed_py
scoreboard players add #ly shahed 16
scoreboard players operation #lz shahed = @s shahed_pz
""")
i = s.index("scoreboard players operation #ld shahed = #s shahed\n") + len("scoreboard players operation #ld shahed = #s shahed\n")
s = s[:i] + """# точка звука: 3 блока от уха в сторону источника (без поиска сущностей)
scoreboard players operation #ox shahed = #ex shahed
scoreboard players operation #ox shahed *= #300 shahed
scoreboard players operation #ox shahed /= #ld shahed
scoreboard players operation #oy shahed = #ey shahed
scoreboard players operation #oy shahed *= #300 shahed
scoreboard players operation #oy shahed /= #ld shahed
scoreboard players add #oy shahed 160
scoreboard players operation #oz shahed = #ez shahed
scoreboard players operation #oz shahed *= #300 shahed
scoreboard players operation #oz shahed /= #ld shahed
execute store result storage shahed:tmp snd.x double 0.01 run scoreboard players get #ox shahed
execute store result storage shahed:tmp snd.y double 0.01 run scoreboard players get #oy shahed
execute store result storage shahed:tmp snd.z double 0.01 run scoreboard players get #oz shahed
# Доплер: скорость сближения за 3 тика; у слушателя 4 ячейки памяти — соседние цели в залпе не сбивают друг друга
scoreboard players set #vr shahed 0
execute if score #nodop shahed matches 0 run function shahed:snd/dop
# скорость звука 343 м/с = 51.5 блока за 3 тика
scoreboard players set #C shahed 515
scoreboard players set #p shahed 100
execute if score #kind shahed matches 1 if score #sph shahed matches 1 run scoreboard players set #p shahed 112
scoreboard players operation #den shahed = #C shahed
scoreboard players operation #den shahed += #vr shahed
execute if score #den shahed matches ..60 run scoreboard players set #den shahed 60
scoreboard players operation #p shahed *= #C shahed
scoreboard players operation #p shahed /= #den shahed
execute if score #p shahed matches ..49 run scoreboard players set #p shahed 50
execute if score #p shahed matches 201.. run scoreboard players set #p shahed 200
# громкость ~ 1/расстояние: шахед на полную с 60 блоков, ракета с 90, B-2 со 120, бомба с 40
execute if score #kind shahed matches 1 run scoreboard players set #g shahed 60000
execute if score #kind shahed matches 2 run scoreboard players set #g shahed 90000
execute if score #kind shahed matches 3 run scoreboard players set #g shahed 120000
execute if score #kind shahed matches 4 run scoreboard players set #g shahed 40000
scoreboard players operation #g shahed /= #ld shahed
execute if score #g shahed matches ..11 run scoreboard players set #g shahed 12
execute if score #g shahed matches 101.. run scoreboard players set #g shahed 100
# тембр по дальности: рядом — полный, дальше воздух «съедает» верха
scoreboard players set #var shahed 0
execute if score #ld shahed matches 450.. run scoreboard players set #var shahed 1
execute if score #ld shahed matches 1300.. run scoreboard players set #var shahed 2
# свист ракеты на последних 260 блоках — пока она приближается; пролетела мимо — свист обрывается
execute if score #kind shahed matches 2 if score #wd shahed matches ..2600 if score #m6 shahed matches 0 unless score #vr shahed matches 1.. run function shahed:snd/whistle
execute if score #kind shahed matches 2 if score #wd shahed matches ..2600 if score #vr shahed matches 1.. run stopsound @s ambient snassets:weapons/bomb_whistle
scoreboard players set #cm shahed 0
execute if score @s shahed_mode matches 1.. if score #kind shahed matches ..2 run scoreboard players set #cm shahed 1
execute if score @s shahed_mode matches 2.. run scoreboard players set #cm shahed 1
execute if score #cm shahed matches 1 run function shahed:snd/play_custom
execute if score #cm shahed matches 0 run function shahed:snd/play_fallback
execute if score #kind shahed matches 2 if score #nodop shahed matches 0 if score #ld shahed matches ..320 unless score @s shahed_fbid = #sid shahed run function shahed:snd/missile_pass
"""
wr("snd/listener", s)

wr("snd/dop", """scoreboard players operation #slot shahed = #sid shahed
scoreboard players operation #slot shahed %= #4 shahed
execute if score #slot shahed matches 0 run return run function shahed:snd/dop0
execute if score #slot shahed matches 1 run return run function shahed:snd/dop1
execute if score #slot shahed matches 2 run return run function shahed:snd/dop2
function shahed:snd/dop3
""")
for k in range(4):
    wr(f"snd/dop{k}", f"""execute unless score @s shahed_sid{k} = #sid shahed run scoreboard players operation @s shahed_pd{k} = #ld shahed
scoreboard players operation @s shahed_sid{k} = #sid shahed
scoreboard players operation #vr shahed = #ld shahed
scoreboard players operation #vr shahed -= @s shahed_pd{k}
scoreboard players operation @s shahed_pd{k} = #ld shahed
""")

wr("snd/play", "$playsound $(e) ambient @s ~$(x) ~$(y) ~$(z) $(g) $(p) 0\n")
wr("snd/whistle", """# чем ближе ракета к цели, тем ниже тон; громкость — по расстоянию до слушателя; направление — на ракету
scoreboard players operation #wp shahed = #wd shahed
scoreboard players operation #wp shahed *= #140 shahed
scoreboard players set #k shahed 2600
scoreboard players operation #wp shahed /= #k shahed
scoreboard players add #wp shahed 60
execute if score #wp shahed matches 201.. run scoreboard players set #wp shahed 200
scoreboard players set #wg shahed 110000
scoreboard players operation #wg shahed /= #ld shahed
execute if score #wg shahed matches ..19 run scoreboard players set #wg shahed 20
execute if score #wg shahed matches 101.. run scoreboard players set #wg shahed 100
execute store result storage shahed:tmp snd.wp double 0.01 run scoreboard players get #wp shahed
execute store result storage shahed:tmp snd.wg double 0.01 run scoreboard players get #wg shahed
stopsound @s ambient snassets:weapons/bomb_whistle
function shahed:snd/whistle_m with storage shahed:tmp snd
""")
wr("snd/whistle_m", "$playsound snassets:weapons/bomb_whistle ambient @s ~$(x) ~$(y) ~$(z) $(wg) $(wp) 0\n")

# source / tails: no src tag, add #m12
for p in ["snd/source", "snd/tail_drone", "snd/tail_missile"]:
    s = rd(p)
    s = s.replace("tag @s add shahed_src\n", "").replace("tag @s remove shahed_src\n", "")
    s = s.replace("scoreboard players operation #m6 shahed %= #6 shahed\n",
                  "scoreboard players operation #m6 shahed %= #6 shahed\nscoreboard players operation #m12 shahed = @s shahed_t\nscoreboard players operation #m12 shahed %= #12 shahed\n")
    assert "#m12" in s and "shahed_src" not in s, p
    wr(p, s)
# fallback: re-trigger every 12 ticks (samples 0.7–3 s long, fewer overlapping copies)
sub("snd/play_fallback", "# без пакета: те же направление, дальность и Доплер, но звуки из модов (раз в 6 тиков)\nexecute unless score #m6 shahed matches 0 run return 0",
    "# без пакета: те же направление, дальность и Доплер, но звуки из модов (раз в 12 тиков — меньше наложений)\nexecute unless score #m12 shahed matches 0 run return 0")
for p in ["snd/fb_jet", "snd/fb_fall"]:
    sub(p, 'data modify storage shahed:tmp snd.e set value "minecraft:item.elytra.flying"',
        'data modify storage shahed:tmp snd.e set value "minecraft:item.elytra.flying"\nstopsound @s ambient minecraft:item.elytra.flying')

# ---------- tracking ----------
wr("track", """# цель — игрок: каждый тик его текущая позиция (центр тела); ушёл в другой мир или далеко — держим последнюю точку
$execute unless entity @a[name="$(who)",distance=..3000] run return 0
$scoreboard players operation #t shahed = @a[name="$(who)",distance=..3000,limit=1] shahed_px
scoreboard players operation #t shahed *= #10 shahed
execute store result score #o shahed run data get entity @s data.wo.x
scoreboard players operation #t shahed += #o shahed
execute store result entity @s data.tx double 0.01 run scoreboard players get #t shahed
$scoreboard players operation #t shahed = @a[name="$(who)",distance=..3000,limit=1] shahed_py
scoreboard players operation #t shahed *= #10 shahed
scoreboard players add #t shahed 100
execute store result entity @s data.ty double 0.01 run scoreboard players get #t shahed
$scoreboard players operation #t shahed = @a[name="$(who)",distance=..3000,limit=1] shahed_pz
scoreboard players operation #t shahed *= #10 shahed
execute store result score #o shahed run data get entity @s data.wo.z
scoreboard players operation #t shahed += #o shahed
execute store result entity @s data.tz double 0.01 run scoreboard players get #t shahed
function shahed:track_scores
""")
wr("track_te", """# цель — сущность (моб, поезд, механизм): одно чтение позиции за тик
$execute unless entity @e[tag=shahed_te$(id),distance=..3000] run return 0
$data modify storage shahed:tmp tp set from entity @e[tag=shahed_te$(id),distance=..3000,limit=1] Pos
execute store result score #a shahed run data get storage shahed:tmp tp[0] 100
$scoreboard players set #b shahed $(ox)
scoreboard players operation #a shahed += #b shahed
execute store result entity @s data.tx double 0.01 run scoreboard players get #a shahed
execute store result score #a shahed run data get storage shahed:tmp tp[1] 100
$scoreboard players set #b shahed $(oy)
scoreboard players operation #a shahed += #b shahed
execute store result entity @s data.ty double 0.01 run scoreboard players get #a shahed
execute store result score #a shahed run data get storage shahed:tmp tp[2] 100
$scoreboard players set #b shahed $(oz)
scoreboard players operation #a shahed += #b shahed
execute store result entity @s data.tz double 0.01 run scoreboard players get #a shahed
function shahed:track_scores
""")
s = rd("track_slp")
wr("track_slp", "scoreboard players set #ok shahed 0\n" + s)

# fly/measure: clamp to avoid int overflow in squares
sub("fly/measure", "scoreboard players operation #dz shahed -= #z shahed\n",
    """scoreboard players operation #dz shahed -= #z shahed
# дальше 2.5 км — зажимаем, чтобы квадраты не переполнили int
execute if score #dx shahed matches 25001.. run scoreboard players set #dx shahed 25000
execute if score #dx shahed matches ..-25001 run scoreboard players set #dx shahed -25000
execute if score #dy shahed matches 25001.. run scoreboard players set #dy shahed 25000
execute if score #dy shahed matches ..-25001 run scoreboard players set #dy shahed -25000
execute if score #dz shahed matches 25001.. run scoreboard players set #dz shahed 25000
execute if score #dz shahed matches ..-25001 run scoreboard players set #dz shahed -25000
""")

# ---------- target tags lifecycle ----------
wr("untag_if_free", """# снять пометку с цели, если за ней больше никто не летит (другой снаряд или незаконченный залп)
$execute if entity @e[type=minecraft:marker,tag=shahed_root,tag=!shahed_dead,nbt={data:{sl:{id:$(id)}}}] run return 0
$execute if entity @e[type=minecraft:marker,tag=shahed_salvo,tag=!shahed_dead,nbt={data:{sl:{id:$(id)}}}] run return 0
$tag @e[tag=shahed_te$(id)] remove shahed_te$(id)
""")
for p in ["drone/detonate", "missile/detonate"]:
    sub(p, "execute if data entity @s data.sl.id run function shahed:untag with entity @s data.sl",
        "tag @s add shahed_dead\nexecute if data entity @s data.sl.id run function shahed:untag_if_free with entity @s data.sl")
sub("salvo/done", "kill @s", "tag @s add shahed_dead\nexecute if data entity @s data.sl.id run function shahed:untag_if_free with entity @s data.sl\nkill @s")
sub("bunker/launch_common", 'execute unless entity @e[type=minecraft:marker,tag=shahed_tgt_new] run return run tellraw @s {"text":"Цель не найдена","color":"red"}\n',
    'execute unless entity @e[type=minecraft:marker,tag=shahed_tgt_new] run return run tellraw @s {"text":"Цель не найдена","color":"red"}\ndata remove storage shahed:tmp d\n')
sub("bunker/launch_common", "kill @e[type=minecraft:marker,tag=shahed_tgt_new]\n",
    "kill @e[type=minecraft:marker,tag=shahed_tgt_new]\n# бомба бьёт по точке и за целью не следит — пометку с сущности снимаем\nexecute if data storage shahed:tmp sl.id run function shahed:untag_if_free with storage shahed:tmp sl\n")
sub("untag_all", "перебираем последние 64", "перебираем последние 256")
sub("untag_loop", "scoreboard players remove #lim shahed 64", "scoreboard players remove #lim shahed 256")

# ray: stale storage
sub("ray/hit_sl", "return 1", "execute unless entity @e[type=minecraft:marker,tag=shahed_tgt_new] run data remove storage shahed:tmp sl\nreturn 1")
sub("menu/fire_look", "data modify storage shahed:tmp sv.sl set from storage shahed:tmp sl",
    "data remove storage shahed:tmp sv.sl\ndata modify storage shahed:tmp sv.sl set from storage shahed:tmp sl")

# quoted names
for p in ["strike", "missile_strike", "bunker_strike", "menu/fire_at"]:
    s = rd(p)
    s2 = s.replace("@a[name=$(name)]", '@a[name="$(name)"]').replace("@a[name=$(name),limit=1]", '@a[name="$(name)",limit=1]')
    assert s2 != s, p
    wr(p, s2)
for p in ["strike", "missile_strike"]:
    sub(p, "data remove storage shahed:tmp who\ndata remove storage shahed:tmp who\n", "data remove storage shahed:tmp who\n")

# temp markers get the common tag (clear removes them, rays ignore them)
for root, dirs, files in os.walk(F):
    for fn in files:
        fp = os.path.join(root, fn)
        s = open(fp, encoding="utf-8").read()
        s2 = s
        for t in ["shahed_tgt_new", "shahed_tgt_surf", "shahed_tmp", "shahed_slp"]:
            s2 = s2.replace('Tags:["%s"]' % t, 'Tags:["shahed","%s"]' % t)
        if s2 != s:
            open(fp, "w", encoding="utf-8").write(s2)

# ---------- clear ----------
sub("clear", "execute as @e[type=minecraft:marker,tag=shahed_lamp] at @s if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air\n",
    """execute as @e[type=minecraft:marker,tag=shahed_lamp] at @s if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air
execute as @e[type=minecraft:marker,tag=shahed_fx] at @s run function shahed:fx/lights_off
execute as @e[type=minecraft:marker,tag=shahed_mfx] at @s run function shahed:mfx/lights_off
execute as @e[type=minecraft:marker,tag=shahed_bfx] at @s run function shahed:bfx/lights_off
""")
sub("clear", "stopsound @a master shahed:siren\n", "stopsound @a master shahed:siren\nstopsound @a master snassets:signal/siren\nscoreboard players set #siren_t shahed -100000\n")

# ---------- siren cooldown (sample is 13 s) ----------
for p, msg in [("drone/siren", "⚠ ВОЗДУШНАЯ ТРЕВОГА ⚠"), ("missile/siren", "⚠ РАКЕТНАЯ ОПАСНОСТЬ ⚠")]:
    s = rd(p)
    wr(p, """# сирена ещё звучит (13 с) — вторую не накладываем, только надпись
execute store result score #now shahed run time query gametime
scoreboard players operation #dt shahed = #now shahed
scoreboard players operation #dt shahed -= #siren_t shahed
execute if score #dt shahed matches 0..259 run return run title @a[distance=..350] actionbar {"text":"%s","color":"red","bold":true}
scoreboard players operation #siren_t shahed = #now shahed
""" % msg + s)

# ---------- bunker ----------
sub("bfx/impact", "stopsound @a[distance=..400] ambient", "stopsound @a[distance=..60] ambient")
sub("bunker/drill_visual", "execute if score #m3 shahed matches 0 run playsound minecraft:entity.warden.dig ambient",
    "scoreboard players operation #m12 shahed = @s shahed_t\nscoreboard players operation #m12 shahed %= #12 shahed\nexecute if score #m12 shahed matches 0 run playsound minecraft:entity.warden.dig ambient")
sub("bfx/cavity", 'blk:"minecraft:ice"', 'blk:"minecraft:black_stained_glass"')
sub("bfx/rubble", "minecraft:gravel replace minecraft:ice", "minecraft:gravel replace minecraft:black_stained_glass")
sub("bfx/rubble", "minecraft:cobblestone replace minecraft:ice", "minecraft:cobblestone replace minecraft:black_stained_glass")
sub("bfx/lights_on", "positioned ~0 ~-4 ~-5", "positioned ~0 ~2 ~-5")
sub("bfx/lights_off", "positioned ~0 ~-4 ~-5", "positioned ~0 ~2 ~-5")

# ---------- debris ----------
wr("debris/land_check", """# сначала дешёвая проверка блока, NBT — только когда под обломком что-то твёрдое
execute if block ~ ~-1.2 ~ #shahed:passable run return 0
execute store result score #vy shahed run data get entity @s Motion[1] 100
execute if score #vy shahed matches ..-5 run function shahed:debris/thud
""")

# ---------- UX ----------
s = rd("rp/prompt")
s = s.replace('"Проиграет 2 секунды мотора шахеда и свиста ракеты"', '"2 секунды: гул бомбардировщика B-2, затем свист падающей бомбы"')
s = s.rstrip("\n") + '\ntellraw @s {"text":"   Звуки идут по ползунку «Окружение» (Настройки → Музыка и звуки) — не держи его на нуле.","color":"dark_gray"}\n'
wr("rp/prompt", s)

s = rd("help")
s = s.replace('{"text":"/data modify storage shahed:cfg power set value 12","color":"aqua"},{"text":"  (шахед, 1–127, TNT = 4)","color":"gray"}',
              '{"text":"/data modify storage shahed:cfg power set value 12","color":"aqua","clickEvent":{"action":"suggest_command","value":"/data modify storage shahed:cfg power set value 12"}},{"text":"  (шахед, 1–60, TNT = 4)","color":"gray"}')
s = s.replace('{"text":"/data modify storage shahed:cfg missile_power set value 20","color":"aqua"}',
              '{"text":"/data modify storage shahed:cfg missile_power set value 20","color":"aqua","clickEvent":{"action":"suggest_command","value":"/data modify storage shahed:cfg missile_power set value 20"}}')
assert "1–60" in s and s.count("suggest_command") >= 2
# clickable command lines
for cmd in ["launch", "missile", "bunker", "clear", "sound_all"]:
    s = s.replace('{"text":"/function shahed:%s","color":"yellow"}' % cmd,
                  '{"text":"/function shahed:%s","color":"yellow","clickEvent":{"action":"suggest_command","value":"/function shahed:%s"}}' % (cmd, cmd))
for cmd, arg in [("strike", '{name:\\"Ник\\"}'), ("missile_strike", '{name:\\"Ник\\"}'), ("bunker_strike", '{name:\\"Ник\\"}'),
                 ("salvo", '{type:\\"drone\\",count:6,radius:25}'), ("salvo_look", '{type:\\"missile\\",count:4,radius:30}')]:
    old = '{"text":"/function shahed:%s %s","color":"yellow"}' % (cmd, arg)
    if old in s:
        s = s.replace(old, '{"text":"/function shahed:%s %s","color":"yellow","clickEvent":{"action":"suggest_command","value":"/function shahed:%s %s"}}' % (cmd, arg, cmd, arg))
    else:
        print("help: no line for", cmd)
wr("help", s)

# ---------- load ----------
s = rd("load")
lines = s.split("\n")
seen = set(); out = []
for ln in lines:
    m = re.match(r"scoreboard players set (#-?\d+) shahed (-?\d+)$", ln)
    if m:
        if m.group(1) in seen: continue
        seen.add(m.group(1))
    out.append(ln)
s = "\n".join(out)
add = ["scoreboard objectives add shahed_px dummy", "scoreboard objectives add shahed_py dummy", "scoreboard objectives add shahed_pz dummy"]
for k in range(4):
    add += [f"scoreboard objectives add shahed_pd{k} dummy", f"scoreboard objectives add shahed_sid{k} dummy"]
add += ["scoreboard players set #12 shahed 12" if "#12" not in seen else "",
        "execute unless score #siren_t shahed = #siren_t shahed run scoreboard players set #siren_t shahed -100000"]
s = s.rstrip("\n") + "\n" + "\n".join(a for a in add if a) + "\n"
wr("load", s)

# ---------- passable ----------
tp = os.path.join(D, "data/shahed/tags/block/passable.json")
j = json.load(open(tp, encoding="utf-8"))
for v in ["#minecraft:small_flowers", "#minecraft:tall_flowers", "#minecraft:saplings", "#minecraft:wool_carpets",
          "#minecraft:rails", "#minecraft:crops", "minecraft:moss_carpet", "minecraft:pink_petals", "minecraft:sweet_berry_bush",
          "minecraft:torch", "minecraft:wall_torch", "minecraft:soul_torch", "minecraft:soul_wall_torch",
          "minecraft:redstone_torch", "minecraft:redstone_wall_torch", "minecraft:lever", "#minecraft:buttons",
          "minecraft:tripwire", "minecraft:redstone_wire", "minecraft:cave_vines", "minecraft:cave_vines_plant", "minecraft:hanging_roots"]:
    if v not in j["values"]: j["values"].append(v)
json.dump(j, open(tp, "w", encoding="utf-8"), indent=2, ensure_ascii=False)
print("patch_review OK")
