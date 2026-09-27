# Блок света неразрушим и гасит лучи взрыва: зажигаем его через тик после взрыва, а не в момент.
import os, sys
FN = sys.argv[1] + "/data/shahed/function"
def rd(n): return open(f"{FN}/{n}.mcfunction", encoding="utf-8").read()
def wr(n, t): open(f"{FN}/{n}.mcfunction", "w", encoding="utf-8").write(t.strip("\n") + "\n")
for start, tick, pre in [("fx/start","fx/tick","fx"), ("mfx/start","mfx/tick","mfx"), ("bfx/start","bfx/tick","bfx")]:
    s = rd(start)
    if f"function shahed:{pre}/lights_on\n" in s:
        wr(start, s.replace(f"function shahed:{pre}/lights_on\n", ""))
    t = rd(tick)
    if f"{pre}/lights_on" not in t:
        t = t.replace("scoreboard players add @s shahed_t 1\n", f"scoreboard players add @s shahed_t 1\nexecute if score @s shahed_t matches 1 run function shahed:{pre}/lights_on\n", 1)
        wr(tick, t)
wr("bfx/lights_on", "\n".join(f"execute positioned ~{x} ~{y} ~{z} if block ~ ~ ~ #minecraft:air run setblock ~ ~ ~ minecraft:light[level=15]"
                               for x,y,z in [(0,2,0),(5,2,0),(-5,2,0),(0,2,5),(0,-4,-5),(0,5,0)]))
wr("bfx/lights_off", "\n".join(f"execute positioned ~{x} ~{y} ~{z} if block ~ ~ ~ minecraft:light run setblock ~ ~ ~ minecraft:air"
                                for x,y,z in [(0,2,0),(5,2,0),(-5,2,0),(0,2,5),(0,-4,-5),(0,5,0)]) +
   "\neffect clear @a[tag=shahed_nv] minecraft:night_vision\ntag @a remove shahed_nv")
print("ok")
