# «Куда навёлся — туда и летит»: луч проверяет аппараты Create (блоки Sable), любые сущности и обычные блоки.
# Снаряд потом следует за целью: за конкретным блоком аппарата или за сущностью.
import os, sys
FN = sys.argv[1] + "/data/shahed/function"
def rd(n): return open(f"{FN}/{n}.mcfunction", encoding="utf-8").read()
def wr(n, t):
    p = f"{FN}/{n}.mcfunction"; os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w", encoding="utf-8").write(t.strip("\n") + "\n")
def rm(n):
    p = f"{FN}/{n}.mcfunction"
    if os.path.exists(p): os.remove(p)
def sub(n, a, b):
    s = rd(n)
    if b in s: return
    assert a in s, (n, a[:70]); wr(n, s.replace(a, b, 1))

ENT = ("@e[dx=0,dy=0,dz=0,tag=!shahed,tag=!shahed_shooter,type=!minecraft:marker,type=!minecraft:item,type=!minecraft:experience_orb,"
       "type=!minecraft:block_display,type=!minecraft:item_display,type=!minecraft:text_display,type=!minecraft:arrow,type=!minecraft:falling_block]")

wr("ray/start", f"""
# луч от глаз: аппарат Create → сущность → блок; что встретится первым, то и цель
kill @e[type=minecraft:marker,tag=shahed_tgt_new]
data remove storage shahed:tmp sl
tag @s add shahed_shooter
execute on vehicle run tag @s add shahed_shooter
scoreboard players set #sln shahed 0
execute centered_in_sub_level @e run scoreboard players set #sln shahed 1
scoreboard players set #steps shahed 0
execute at @s anchored eyes positioned ^ ^ ^0.5 run function shahed:ray/step
tag @s remove shahed_shooter
execute on vehicle run tag @s remove shahed_shooter
execute if data storage shahed:tmp sl.px run tellraw @s {{"text":"⌖ Цель: аппарат Create — снаряд пойдёт за ним","color":"gold"}}
execute if data storage shahed:tmp sl.id run function shahed:ray/say_ent with storage shahed:tmp sl
""")
wr("ray/step", f"""
execute if score #sln shahed matches 1 in_sub_level @n unless block ~ ~ ~ #shahed:passable run return run function shahed:ray/hit_sl
execute if score #steps shahed matches 3.. positioned ~-0.5 ~-0.5 ~-0.5 if entity {ENT} positioned ~0.5 ~0.5 ~0.5 run return run function shahed:ray/hit_ent
execute unless block ~ ~ ~ #shahed:passable run return run summon minecraft:marker ~ ~ ~ {{Tags:["shahed_tgt_new"]}}
scoreboard players add #steps shahed 1
execute if score #steps shahed matches 400.. positioned over motion_blocking_no_leaves run return run summon minecraft:marker ~ ~ ~ {{Tags:["shahed_tgt_new"]}}
execute positioned ^ ^ ^0.5 run function shahed:ray/step
""")
wr("ray/hit_sl", """
# здесь мы в пространстве аппарата: запоминаем точку попадания в его координатах, цель — её мировое положение
summon minecraft:marker ~ ~ ~ {Tags:["shahed_slp"]}
execute store result storage shahed:tmp sl.px double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_slp,limit=1] Pos[0] 100
execute store result storage shahed:tmp sl.py double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_slp,limit=1] Pos[1] 100
execute store result storage shahed:tmp sl.pz double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_slp,limit=1] Pos[2] 100
kill @e[type=minecraft:marker,tag=shahed_slp]
execute out_sub_level @i run summon minecraft:marker ~ ~ ~ {Tags:["shahed_tgt_new"]}
return 1
""")
wr("ray/hit_ent", f"""
# попали в сущность (игрок, моб, самолёт, поезд или механизм Create): метим её, запоминаем смещение точки попадания
scoreboard players add #tn shahed 1
execute store result storage shahed:tmp sl.id int 1 run scoreboard players get #tn shahed
function shahed:ray/tag_ent with storage shahed:tmp sl
summon minecraft:marker ~ ~ ~ {{Tags:["shahed_tgt_new"]}}
function shahed:ray/offset with storage shahed:tmp sl
return 1
""")
wr("ray/tag_ent", f"$execute positioned ~-0.5 ~-0.5 ~-0.5 run tag {ENT[:-1]},sort=nearest,limit=1] add shahed_te$(id)")
off = []
for i, ax in enumerate("xyz"):
    off.append(f"execute store result score #a shahed run data get entity @e[type=minecraft:marker,tag=shahed_tgt_new,limit=1] Pos[{i}] 100")
    off.append(f"$execute store result score #b shahed run data get entity @e[tag=shahed_te$(id),limit=1] Pos[{i}] 100")
    off.append("scoreboard players operation #a shahed -= #b shahed")
    off.append(f"execute store result storage shahed:tmp sl.o{ax} int 1 run scoreboard players get #a shahed")
wr("ray/offset", "\n".join(off))
wr("ray/say_ent", '$tellraw @s ["",{"text":"⌖ Цель: ","color":"gold"},{"selector":"@e[tag=shahed_te$(id)]","color":"yellow"},{"text":" — снаряд пойдёт за ней","color":"gold"}]')

# ---------- слежение ----------
wr("track_slp", """
$execute store success score #ok shahed positioned $(px) $(py) $(pz) out_sub_level @i run tp @e[type=minecraft:marker,tag=shahed_helper2,limit=1] ~ ~ ~
execute unless score #ok shahed matches 1 run return 0
execute store result entity @s data.tx double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_helper2,limit=1] Pos[0] 100
execute store result entity @s data.ty double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_helper2,limit=1] Pos[1] 100
execute store result entity @s data.tz double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_helper2,limit=1] Pos[2] 100
function shahed:track_scores
""")
tt = ["$execute unless entity @e[tag=shahed_te$(id)] run return 0"]
for i, ax in enumerate("xyz"):
    tt.append(f"$execute store result score #a shahed run data get entity @e[tag=shahed_te$(id),limit=1] Pos[{i}] 100")
    tt.append(f"$scoreboard players set #b shahed $(o{ax})")
    tt.append("scoreboard players operation #a shahed += #b shahed")
    tt.append(f"execute store result entity @s data.t{ax} double 0.01 run scoreboard players get #a shahed")
tt.append("function shahed:track_scores")
wr("track_te", "\n".join(tt))
wr("track_scores", """
execute store result score @s shahed_tx run data get entity @s data.tx 10
execute store result score @s shahed_ty run data get entity @s data.ty 10
execute store result score @s shahed_tz run data get entity @s data.tz 10
""")
wr("untag", "$tag @e[tag=shahed_te$(id)] remove shahed_te$(id)")
for n in ("drone/tick", "missile/tick"):
    s = rd(n).replace("execute if data entity @s data.sl run function shahed:track_sl with entity @s data\n",
                      "execute if data entity @s data.sl.px run function shahed:track_slp with entity @s data.sl\nexecute if data entity @s data.sl.id run function shahed:track_te with entity @s data.sl\n")
    assert "track_slp" in s, n; wr(n, s)
for n in ("drone/detonate", "missile/detonate"):
    sub(n, "scoreboard players operation #cur shahed_id = @s shahed_id\n",
        "scoreboard players operation #cur shahed_id = @s shahed_id\nexecute if data entity @s data.sl.id run function shahed:untag with entity @s data.sl\n")
for n in ("track_sl", "track_sl_find", "ray/sublevel"):
    rm(n)
# очистка пометок у сущностей при «Отбое»
sub("clear", "kill @e[tag=shahed]\n", "kill @e[tag=shahed]\nfunction shahed:untag_all\n")
wr("untag_all", "# снять пометки целей: у сущностей бывают только теги shahed_te<N>; перебираем последние 64\n" +
   "\n".join(f"tag @e[tag=shahed_te{i}] remove shahed_te{i}" for i in range(1, 1)) +
   "execute store result storage shahed:tmp ua.n int 1 run scoreboard players get #tn shahed\nfunction shahed:untag_loop with storage shahed:tmp ua")
wr("untag_loop", """
$tag @e[tag=shahed_te$(n)] remove shahed_te$(n)
$scoreboard players set #u shahed $(n)
scoreboard players remove #u shahed 1
execute if score #u shahed matches ..0 run return 0
scoreboard players operation #lim shahed = #tn shahed
scoreboard players remove #lim shahed 64
execute if score #u shahed <= #lim shahed run return 0
execute store result storage shahed:tmp ua.n int 1 run scoreboard players get #u shahed
function shahed:untag_loop with storage shahed:tmp ua
""")
print("ok")

# запасной путь, если «@i» (аппарат, содержащий точку) не сработает — ближайший аппарат
sub("ray/hit_sl", "execute out_sub_level @i run summon minecraft:marker ~ ~ ~ {Tags:[\"shahed_tgt_new\"]}\n",
    "execute out_sub_level @i run summon minecraft:marker ~ ~ ~ {Tags:[\"shahed_tgt_new\"]}\nexecute unless entity @e[type=minecraft:marker,tag=shahed_tgt_new] out_sub_level @n run summon minecraft:marker ~ ~ ~ {Tags:[\"shahed_tgt_new\"]}\n")
sub("track_slp", "execute unless score #ok shahed matches 1 run return 0\n",
    "$execute unless score #ok shahed matches 1 store success score #ok shahed positioned $(px) $(py) $(pz) out_sub_level @n run tp @e[type=minecraft:marker,tag=shahed_helper2,limit=1] ~ ~ ~\nexecute unless score #ok shahed matches 1 run return 0\n")
print("ok3")

s = rd("ray/start")
if "centered_in_sub_level @v" not in s:
    s = s.replace("scoreboard players set #steps shahed 0\nexecute at @s anchored eyes positioned ^ ^ ^0.5 run function shahed:ray/step\n",
        "execute if score #sln shahed matches 1 at @s centered_in_sub_level @v run function shahed:ray/hit_sl\nscoreboard players set #steps shahed 0\n"
        "execute unless entity @e[type=minecraft:marker,tag=shahed_tgt_new] at @s anchored eyes positioned ^ ^ ^0.5 run function shahed:ray/step\n")
    wr("ray/start", s)
