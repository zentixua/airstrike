# Финальная полировка: реалистичные скорости, баланс, оптимизация выборок, чистка отладки.
import os, sys, re
DP = sys.argv[1]; FN = DP + "/data/shahed/function"
def rd(n): return open(f"{FN}/{n}.mcfunction", encoding="utf-8").read()
def wr(n, t):
    p = f"{FN}/{n}.mcfunction"; os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w", encoding="utf-8").write(t.strip("\n") + "\n")
def rm(n):
    p = f"{FN}/{n}.mcfunction"
    if os.path.exists(p): os.remove(p)
def rep(n, a, b, must=True):
    s = rd(n)
    if a not in s:
        if must and b not in s: raise SystemExit(f"{n}: не найдено {a[:80]!r}")
        return
    wr(n, s.replace(a, b))

# ---------- 1. ракета: дозвуковая крылатая, ~830–900 км/ч (11.5–12.5 блока/тик) ----------
rep("missile/init", "scoreboard players set @s shahed_v 420", "scoreboard players set @s shahed_v 1150")
rep("missile/steer", "execute if score @s shahed_ph matches 1 if score #los shahed matches 1400.. run", "execute if score @s shahed_ph matches 1 if score #los shahed matches 2400.. run")
rep("missile/steer", "scoreboard players add #t2 shahed 2600", "scoreboard players add #t2 shahed 3200")
rep("missile/steer", "# 0 — бреющий, 1 — горка с 160 блоков (до +26 над целью), 2 — пикирование по дуге",
    "# 0 — бреющий полёт, 1 — горка с 160 блоков (до +32 над целью), 2 — пикирование по дуге")
wr("missile/cruise_cmd", """
function shahed:fly/terrain_far
scoreboard players operation #H shahed = #tmax shahed
scoreboard players add #H shahed 1200
scoreboard players operation #t2 shahed = @s shahed_ty
scoreboard players operation #t2 shahed *= #10 shahed
scoreboard players add #t2 shahed 1200
scoreboard players operation #H shahed > #t2 shahed
function shahed:fly/alt_cmd
scoreboard players operation #wcmd shahed = #thd shahed
scoreboard players operation #wcmd shahed -= #cp shahed
scoreboard players operation #wcmd shahed *= #30 shahed
scoreboard players operation #wcmd shahed /= #100 shahed
scoreboard players set #W shahed 800
scoreboard players set #A shahed 180
""")
wr("missile/pop_cmd", """
scoreboard players set #wcmd shahed -2000
scoreboard players operation #wcmd shahed -= #cp shahed
scoreboard players operation #wcmd shahed *= #30 shahed
scoreboard players operation #wcmd shahed /= #100 shahed
scoreboard players set #W shahed 800
scoreboard players set #A shahed 180
""")
wr("missile/dive_cmd", """
function shahed:fly/arc_cmd
scoreboard players set #W shahed 1600
scoreboard players set #A shahed 350
scoreboard players add @s shahed_v 10
execute if score @s shahed_v matches 1251.. run scoreboard players set @s shahed_v 1250
""")
# рельеф на скорости ракеты смотрим дальше: 30 / 60 / 90 блоков
t = rd("fly/terrain")
wr("fly/terrain_far", t.replace("^ ^ ^15", "^ ^ ^30").replace("^ ^ ^45", "^ ^ ^90").replace("^ ^ ^30 positioned over motion_blocking run tp @e[type=minecraft:marker,tag=shahed_helper2,limit=1] ~ ~ ~\nexecute store result score #th shahed run data get entity @e[type=minecraft:marker,tag=shahed_helper2,limit=1] Pos[1] 100\nscoreboard players operation #tmax shahed > #th shahed\nexecute rotated as @s rotated ~ 0 positioned ^ ^ ^30",
    "^ ^ ^30 positioned over motion_blocking run tp @e[type=minecraft:marker,tag=shahed_helper2,limit=1] ~ ~ ~\nexecute store result score #th shahed run data get entity @e[type=minecraft:marker,tag=shahed_helper2,limit=1] Pos[1] 100\nscoreboard players operation #tmax shahed > #th shahed\nexecute rotated as @s rotated ~ 0 positioned ^ ^ ^60").replace("15/30/45", "30/60/90"))
tf = rd("fly/terrain_far")
assert "^ ^ ^30" in tf and "^ ^ ^60" in tf and "^ ^ ^90" in tf, tf

# ---------- 2. бомба: у земли ~900 км/ч (12.5 блока/тик) ----------
rep("bunker/steer", "scoreboard players add @s shahed_v 20\nexecute if score @s shahed_v matches 951.. run scoreboard players set @s shahed_v 950",
    "scoreboard players add @s shahed_v 30\nexecute if score @s shahed_v matches 1251.. run scoreboard players set @s shahed_v 1250")

# ---------- 3. столкновения: 12 точек на шаг, «неконтактный» подрыв по трём точкам вдоль шага ----------
mp = ["# контрольные точки столкновения от носа на длину шага; #nose — полудлина корпуса (сантиблоки)"]
for k in range(1, 13):
    mp += ["scoreboard players operation #c shahed = @s shahed_v", f"scoreboard players set #k shahed {k}",
           "scoreboard players operation #c shahed *= #k shahed", "scoreboard players set #k shahed 12",
           "scoreboard players operation #c shahed /= #k shahed", "scoreboard players operation #c shahed += #nose shahed",
           f"execute store result storage shahed:tmp mv.c{k} double 0.01 run scoreboard players get #c shahed"]
for k, frac in ((1, 4), (2, 2), (3, 4)):
    mul = 3 if k == 3 else 1
    mp += ["scoreboard players operation #c shahed = @s shahed_v", f"scoreboard players set #k shahed {mul}",
           "scoreboard players operation #c shahed *= #k shahed", f"scoreboard players set #k shahed {frac}",
           "scoreboard players operation #c shahed /= #k shahed", "scoreboard players operation #c shahed += #nose shahed",
           f"execute store result storage shahed:tmp mv.m{k} double 0.01 run scoreboard players get #c shahed"]
mp += ["execute store result storage shahed:tmp mv.v double 0.01 run scoreboard players get @s shahed_v",
       "scoreboard players operation #c shahed = @s shahed_v", "scoreboard players set #k shahed 8", "scoreboard players operation #c shahed /= #k shahed",
       "scoreboard players add #c shahed 180", "execute store result storage shahed:tmp mv.r double 0.01 run scoreboard players get #c shahed",
       "return run function shahed:fly/move_m with storage shahed:tmp mv"]
wr("fly/move_prep", "\n".join(mp))
wr("fly/move_m", "\n".join(
    [f"$execute positioned ^ ^ ^$(c{k}) unless block ~ ~ ~ #shahed:passable run return run function $(det)" for k in range(1, 13)] +
    [f"$execute positioned ^ ^ ^$(m{k}) if entity @a[distance=..$(r),gamemode=!spectator] run return run function $(det)" for k in (1, 2, 3)] +
    ["$tp @s ^ ^ ^$(v)", "$execute at @s run function $(vis)", "return 0"]))

# ---------- 4. ракета: сплошной след на всю длину шага, звук чаще, Доплер по настоящей скорости звука ----------
s = rd("missile/visual")
s = s.replace("particle minecraft:end_rod ^ ^ ^-6.4 0.03 0.03 0.03 0.002 2 force @a\n",
              "".join(f"particle minecraft:end_rod ^ ^ ^-{d} 0.03 0.03 0.03 0.002 1 force @a\n" for d in (6.4, 8.5, 10.5, 12.5, 14.5, 16.5)))
s = s.replace("particle minecraft:campfire_cosy_smoke ^ ^ ^-7.0 0.05 0.05 0.05 0.003 2 force @a\n",
              "".join(f"particle minecraft:campfire_cosy_smoke ^ ^ ^-{d} 0.05 0.05 0.05 0.003 1 force @a\n" for d in (7, 10, 13, 16)))
s = s.replace("particle minecraft:cloud ^ ^ ^-7.4 0.04 0.04 0.04 0.003 1 force @a\n",
              "".join(f"particle minecraft:cloud ^ ^ ^-{d} 0.04 0.04 0.04 0.003 1 force @a\n" for d in (7.4, 11, 15)))
s = s.replace("scoreboard players operation #m3 shahed %= #3 shahed", "scoreboard players operation #m3 shahed %= #2 shahed")
wr("missile/visual", s)
rep("snd/listener", "execute if score #kind shahed matches 2 run scoreboard players set #C shahed 240", "execute if score #kind shahed matches 2 run scoreboard players set #C shahed 515")
rep("snd/listener", "execute if score #kind shahed matches 2 if score #wd shahed matches ..1600 run function shahed:snd/whistle",
    "execute if score #kind shahed matches 2 if score #wd shahed matches ..2600 run function shahed:snd/whistle")
rep("snd/whistle", "scoreboard players operation #wp shahed /= #1600 shahed", "scoreboard players set #k shahed 2600\nscoreboard players operation #wp shahed /= #k shahed")
rep("snd/whistle", "# свист на подлёте последних ~160 блоков", "# свист на подлёте последних ~260 блоков")

# ---------- 5. оптимизация: части модели ищем только рядом с корнем; помощника — в точке корня ----------
for n, rad in (("drone/visual", 16), ("missile/visual", 32), ("bunker/visual", 24), ("bunker/drill_visual", 24), ("bunker/bomber_visual", 48)):
    rep(n, "execute as @e[tag=shahed_part] if score @s shahed_id = #cur shahed_id run tp @s ~ ~ ~ ~ ~",
        f"execute as @e[tag=shahed_part,distance=..{rad}] if score @s shahed_id = #cur shahed_id run tp @s ~ ~ ~ ~ ~")
rep("missile/visual", "execute as @e[type=minecraft:marker,tag=shahed_lamp] if score @s shahed_id = #cur shahed_id at @s if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air",
    "execute as @e[type=minecraft:marker,tag=shahed_lamp,distance=..32] if score @s shahed_id = #cur shahed_id at @s if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air")
rep("missile/visual", "execute as @e[type=minecraft:marker,tag=shahed_lamp] if score @s shahed_id = #cur shahed_id at @s if block ~ ~ ~ #minecraft:air run setblock ~ ~ ~ minecraft:light[level=14]",
    "execute as @e[type=minecraft:marker,tag=shahed_lamp,distance=..32] if score @s shahed_id = #cur shahed_id at @s if block ~ ~ ~ #minecraft:air run setblock ~ ~ ~ minecraft:light[level=14]")
for n in ("fly/look", "bunker/home"):
    s = rd(n).replace("data get entity @e[type=minecraft:marker,tag=shahed_helper,sort=nearest,limit=1]",
                      "data get entity @e[type=minecraft:marker,tag=shahed_helper,distance=..0.01,limit=1]")
    wr(n, s)

# ---------- 6. уборка отладки ----------
for n in ("debug_aim", "ray/diag", "ray/diag_dist"):
    rm(n)
s = rd("ray/start")
s = s.replace("scoreboard players set #cnt shahed 0\nexecute centered_in_sub_level @e run scoreboard players add #cnt shahed 1\nexecute if score #cnt shahed matches 1.. run scoreboard players set #sln shahed 1\n",
              "execute centered_in_sub_level @e run scoreboard players set #sln shahed 1\n")
s = s.replace("scoreboard players set #vv shahed 0\nexecute if score #sln shahed matches 1 at @s centered_in_sub_level @v run scoreboard players set #vv shahed 1\n", "")
s = re.sub(r"\nexecute if score #sln shahed matches 1 if data storage shahed:cfg \{aimdiag:1b\} run function shahed:ray/diag", "", s)
wr("ray/start", s)
s = rd("load")
s = s.replace("execute unless data storage shahed:cfg aimdiag run data modify storage shahed:cfg aimdiag set value 0b\n", "")
s += "data remove storage shahed:cfg aimdiag\n" if "data remove storage shahed:cfg aimdiag" not in s else ""
wr("load", s)
assert "diag" not in rd("ray/start")
print("ok")
