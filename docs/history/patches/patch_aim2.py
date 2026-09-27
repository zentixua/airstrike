# Залп вокруг движущейся цели: каждый снаряд следует за целью со своим смещением (разброс «едет» вместе с ней).
import os, sys
FN = sys.argv[1] + "/data/shahed/function"
def rd(n): return open(f"{FN}/{n}.mcfunction", encoding="utf-8").read()
def wr(n, t):
    p = f"{FN}/{n}.mcfunction"; os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w", encoding="utf-8").write(t.strip("\n") + "\n")
def sub(n, a, b):
    s = rd(n)
    if b in s: return
    assert a in s, (n, a[:70]); wr(n, s.replace(a, b, 1))
sub("salvo/fire", "execute if score #r shahed matches 0 run data modify storage shahed:tmp who set from entity @s data.who\n",
    "data modify storage shahed:tmp who set from entity @s data.who\n")
sub("salvo/fire", "execute if score #r shahed matches 0 run data modify storage shahed:tmp sl set from entity @s data.sl\n",
    "data modify storage shahed:tmp sl set from entity @s data.sl\nfunction shahed:salvo/offset_aim\n")
wr("salvo/offset_aim", """
# смещение этого снаряда относительно движущейся цели
data remove storage shahed:tmp wo
execute store result storage shahed:tmp wo.x int 100 run scoreboard players get #dx shahed
execute store result storage shahed:tmp wo.z int 100 run scoreboard players get #dz shahed
execute if data storage shahed:tmp sl.id run function shahed:salvo/offset_te
execute if data storage shahed:tmp sl.px run function shahed:salvo/offset_slp
""")
wr("salvo/offset_te", """
execute store result score #a shahed run data get storage shahed:tmp sl.ox
scoreboard players operation #b shahed = #dx shahed
scoreboard players operation #b shahed *= #100 shahed
scoreboard players operation #a shahed += #b shahed
execute store result storage shahed:tmp sl.ox int 1 run scoreboard players get #a shahed
execute store result score #a shahed run data get storage shahed:tmp sl.oz
scoreboard players operation #b shahed = #dz shahed
scoreboard players operation #b shahed *= #100 shahed
scoreboard players operation #a shahed += #b shahed
execute store result storage shahed:tmp sl.oz int 1 run scoreboard players get #a shahed
""")
wr("salvo/offset_slp", """
execute store result score #a shahed run data get storage shahed:tmp sl.px 100
scoreboard players operation #b shahed = #dx shahed
scoreboard players operation #b shahed *= #100 shahed
scoreboard players operation #a shahed += #b shahed
execute store result storage shahed:tmp sl.px double 0.01 run scoreboard players get #a shahed
execute store result score #a shahed run data get storage shahed:tmp sl.pz 100
scoreboard players operation #b shahed = #dz shahed
scoreboard players operation #b shahed *= #100 shahed
scoreboard players operation #a shahed += #b shahed
execute store result storage shahed:tmp sl.pz double 0.01 run scoreboard players get #a shahed
""")
# смещение для цели-игрока: в данные снаряда (wo) и учёт в слежении
for n in ("launch_common", "missile/launch_common"):
    sub(n, "data modify storage shahed:tmp d.sl set from storage shahed:tmp sl\n",
        "data modify storage shahed:tmp d.sl set from storage shahed:tmp sl\ndata modify storage shahed:tmp d.wo set from storage shahed:tmp wo\n")
sub("salvo/fire", "scoreboard players set #nosiren shahed 0\ndata remove storage shahed:tmp who\n",
    "scoreboard players set #nosiren shahed 0\ndata remove storage shahed:tmp who\ndata remove storage shahed:tmp wo\n")
for n in ("strike", "missile_strike"):
    sub(n, "data remove storage shahed:tmp sl\n", "data remove storage shahed:tmp sl\ndata remove storage shahed:tmp wo\n")
for n in ("launch", "missile"):
    sub(n, "data remove storage shahed:tmp who\n", "data remove storage shahed:tmp who\ndata remove storage shahed:tmp wo\n")
t = rd("track")
if "data.wo" not in t:
    t = t.replace("scoreboard players add #t shahed 100\n", "scoreboard players add #t shahed 100\n")
    t = t.replace("$execute store result score #t shahed run data get entity @a[name=$(who),limit=1] Pos[0] 100\n",
                  "$execute store result score #t shahed run data get entity @a[name=$(who),limit=1] Pos[0] 100\nexecute store result score #o shahed run data get entity @s data.wo.x\nscoreboard players operation #t shahed += #o shahed\n")
    t = t.replace("$execute store result score #t shahed run data get entity @a[name=$(who),limit=1] Pos[2] 100\n",
                  "$execute store result score #t shahed run data get entity @a[name=$(who),limit=1] Pos[2] 100\nexecute store result score #o shahed run data get entity @s data.wo.z\nscoreboard players operation #t shahed += #o shahed\n")
    assert t.count("data.wo") == 2
    wr("track", t)
print("ok")
