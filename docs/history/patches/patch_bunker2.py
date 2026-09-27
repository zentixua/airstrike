# Бомба v2: естественная форма шахты/полости/провала, разброс попадания, урон ударной волной сквозь породу.
import os, sys, json
DP = sys.argv[1]; FN = DP + "/data/shahed/function"
def rd(n): return open(f"{FN}/{n}.mcfunction", encoding="utf-8").read()
def wr(n, t):
    p = f"{FN}/{n}.mcfunction"; os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w", encoding="utf-8").write(t.strip("\n") + "\n")
for p in (10, 15, 20, 65):
    json.dump({"condition": "minecraft:random_chance", "chance": p/100}, open(f"{DP}/data/shahed/predicate/p{p}.json", "w"))

# ---------- разброс попадания (КВО ~2 блока) ----------
s = rd("bunker/launch_common")
if "bunker/jitter" not in s:
    s = s.replace('execute store result storage shahed:tmp d.tx double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_tgt_surf,limit=1] Pos[0] 100',
'''execute store result storage shahed:tmp j.x double 0.1 run random value -25..25
execute store result storage shahed:tmp j.z double 0.1 run random value -25..25
execute as @e[type=minecraft:marker,tag=shahed_tgt_surf] at @s run function shahed:bunker/jitter with storage shahed:tmp j
execute store result storage shahed:tmp d.tx double 0.01 run data get entity @e[type=minecraft:marker,tag=shahed_tgt_surf,limit=1] Pos[0] 100''', 1)
    wr("bunker/launch_common", s)
wr("bunker/jitter", "$execute positioned ~$(x) ~ ~$(z) positioned over motion_blocking_no_leaves run tp @s ~ ~-0.5 ~")

# ---------- генератор неровных «комьев» ----------
wr("bfx/blobs", """
execute if score #bn shahed matches ..0 run return 0
scoreboard players remove #bn shahed 1
function shahed:bfx/blob_rand with storage shahed:tmp br
function shahed:bfx/blobs
""")
calc = []
for ax, s_ in (("x", "#bx"), ("y", "#by2"), ("z", "#bz")):
    for rr, k in (("#ba", "a"), ("#bb", "b")):
        calc.append(f"scoreboard players operation #q shahed = {s_} shahed\nscoreboard players operation #q shahed -= {rr} shahed\nexecute store result storage shahed:tmp blob.{ax}{k}1 int 1 run scoreboard players get #q shahed")
        calc.append(f"scoreboard players operation #q shahed = {s_} shahed\nscoreboard players operation #q shahed += {rr} shahed\nexecute store result storage shahed:tmp blob.{ax}{k}2 int 1 run scoreboard players get #q shahed")
wr("bfx/blob_rand", """
# случайный центр и размер; форма — «объёмный крест» трёх пересекающихся брусков (скруглённый ком)
$execute store result score #bx shahed run random value -$(rx)..$(rx)
$execute store result score #by2 shahed run random value -$(ry)..$(ry)
$execute store result score #bz shahed run random value -$(rz)..$(rz)
$execute store result score #ba shahed run random value $(rmin)..$(rmax)
$scoreboard players set #oy shahed $(oy)
scoreboard players operation #by2 shahed += #oy shahed
scoreboard players operation #bb shahed = #ba shahed
scoreboard players operation #bb shahed *= #6 shahed
scoreboard players operation #bb shahed /= #10 shahed
execute if score #bb shahed matches ..0 run scoreboard players set #bb shahed 1
""" + "\n".join(calc) + """
data modify storage shahed:tmp blob.blk set from storage shahed:tmp br.blk
data modify storage shahed:tmp blob.rep set from storage shahed:tmp br.rep
function shahed:bfx/blob with storage shahed:tmp blob
""")
wr("bfx/blob", """
$fill ~$(xa1) ~$(yb1) ~$(zb1) ~$(xa2) ~$(yb2) ~$(zb2) $(blk) replace $(rep)
$fill ~$(xb1) ~$(yb1) ~$(za1) ~$(xb2) ~$(yb2) ~$(za2) $(blk) replace $(rep)
$fill ~$(xb1) ~$(ya1) ~$(zb1) ~$(xb2) ~$(ya2) ~$(zb2) $(blk) replace $(rep)
""")

# ---------- шахта: неровная, с отклонениями, дроблёной породой и осыпью ----------
wr("bunker/carve", """
fill ~ ~-1 ~ ~ ~1 ~ minecraft:air replace #shahed:drillable
execute if predicate shahed:p65 run fill ~1 ~ ~ ~1 ~ ~ minecraft:air replace #shahed:drillable
execute if predicate shahed:p65 run fill ~-1 ~ ~ ~-1 ~ ~ minecraft:air replace #shahed:drillable
execute if predicate shahed:p65 run fill ~ ~ ~1 ~ ~ ~1 minecraft:air replace #shahed:drillable
execute if predicate shahed:p65 run fill ~ ~ ~-1 ~ ~ ~-1 minecraft:air replace #shahed:drillable
execute if predicate shahed:p15 run fill ~-1 ~-1 ~-1 ~1 ~1 ~1 minecraft:air replace #shahed:drillable
execute if predicate shahed:p20 run fill ~2 ~-1 ~-1 ~2 ~1 ~1 minecraft:cobblestone replace #shahed:bb_rock
execute if predicate shahed:p20 run fill ~-2 ~-1 ~-1 ~-2 ~1 ~1 minecraft:cobblestone replace #shahed:bb_rock
execute if predicate shahed:p20 run fill ~-1 ~-1 ~2 ~1 ~1 ~2 minecraft:cobbled_deepslate replace #shahed:bb_deep
execute if predicate shahed:p20 run fill ~-1 ~-1 ~-2 ~1 ~1 ~-2 minecraft:gravel replace #shahed:bb_soil
execute if predicate shahed:p10 run fill ~ ~2 ~ ~ ~2 ~ minecraft:gravel replace minecraft:air
particle minecraft:poof ~ ~ ~ 0.4 0.4 0.4 0.05 3 force @a
execute as @a[distance=..1.8] run damage @s 100 minecraft:explosion
""")
s = rd("bunker/step")
if "bunker/wobble" not in s:
    s = s.replace("function shahed:bunker/carve\ntp @s ~ ~ ~\n",
        "function shahed:bunker/carve\ntp @s ~ ~ ~\nexecute if data entity @s data{goal:1b} run function shahed:bunker/home with entity @s data\nfunction shahed:bunker/wobble\n")
    wr("bunker/step", s)
wr("bunker/home", """
# довод на цель под землёй: 30% поправки за блок
$tp @e[type=minecraft:marker,tag=shahed_helper,sort=nearest,limit=1] ~ ~ ~ facing $(gx) $(gy) $(gz)
execute store result score #hy shahed run data get entity @e[type=minecraft:marker,tag=shahed_helper,sort=nearest,limit=1] Rotation[0] 100
execute store result score #hp shahed run data get entity @e[type=minecraft:marker,tag=shahed_helper,sort=nearest,limit=1] Rotation[1] 100
execute store result score #cy shahed run data get entity @s Rotation[0] 100
execute store result score #cp shahed run data get entity @s Rotation[1] 100
scoreboard players operation #hy shahed -= #cy shahed
execute if score #hy shahed matches 18001.. run scoreboard players remove #hy shahed 36000
execute if score #hy shahed matches ..-18001 run scoreboard players add #hy shahed 36000
scoreboard players operation #hp shahed -= #cp shahed
scoreboard players operation #hy shahed *= #3 shahed
scoreboard players operation #hy shahed /= #10 shahed
scoreboard players operation #hp shahed *= #3 shahed
scoreboard players operation #hp shahed /= #10 shahed
scoreboard players operation #cy shahed += #hy shahed
scoreboard players operation #cp shahed += #hp shahed
execute store result entity @s Rotation[0] float 0.01 run scoreboard players get #cy shahed
execute store result entity @s Rotation[1] float 0.01 run scoreboard players get #cp shahed
""")
wr("bunker/wobble", """
# в породе боеприпас «гуляет»: случайные отклонения до 2.5°
execute store result score #cy shahed run data get entity @s Rotation[0] 100
execute store result score #cp shahed run data get entity @s Rotation[1] 100
execute store result score #j shahed run random value -250..250
scoreboard players operation #cy shahed += #j shahed
execute store result score #j shahed run random value -200..200
scoreboard players operation #cp shahed += #j shahed
execute if score #cp shahed matches ..4499 run scoreboard players set #cp shahed 4500
execute if score #cp shahed matches 8901.. run scoreboard players set #cp shahed 8900
execute store result entity @s Rotation[0] float 0.01 run scoreboard players get #cy shahed
execute store result entity @s Rotation[1] float 0.01 run scoreboard players get #cp shahed
""")

# ---------- удар о поверхность: кинетика 13-тонной болванки ----------
s = rd("bfx/impact")
if "damage" not in s:
    s = s.replace("ExplosionRadius:2b", "ExplosionRadius:4b")
    s += """
# кинетический удар: рядом с точкой попадания — смертельно
execute as @e[distance=..4.5,tag=!shahed,type=!minecraft:marker] run damage @s 60 minecraft:explosion
execute as @e[distance=4.5..9,tag=!shahed,type=!minecraft:marker] run damage @s 22 minecraft:explosion
execute as @e[distance=9..14,tag=!shahed,type=!minecraft:marker] run damage @s 7 minecraft:explosion
"""
    wr("bfx/impact", s)

# ---------- подземный взрыв: естественная полость через «ослабление» породы ----------
wr("bfx/cavity", """
# порода вокруг заряда в неровных комьях заменяется льдом: он не даёт дропа и почти не держит взрыв,
# поэтому взрыв сам выгрызает полость рваной, «природной» формы
data modify storage shahed:tmp br set value {rx:6,ry:5,rz:6,oy:0,rmin:3,rmax:5,blk:"minecraft:ice",rep:"#shahed:drillable"}
scoreboard players set #bn shahed 12
function shahed:bfx/blobs
execute store result storage shahed:tmp ck.x int 1 run random value -3..3
execute store result storage shahed:tmp ck.y int 1 run random value -2..2
execute store result storage shahed:tmp ck.z int 1 run random value -3..3
function shahed:bfx/extra_blast with storage shahed:tmp ck
execute store result storage shahed:tmp ck.x int 1 run random value -3..3
execute store result storage shahed:tmp ck.y int 1 run random value -2..2
execute store result storage shahed:tmp ck.z int 1 run random value -3..3
function shahed:bfx/extra_blast with storage shahed:tmp ck
""" + "\n".join(f'summon minecraft:small_fireball ~{x} ~5 ~{z} {{Motion:[0.0d,-1.2d,0.0d],Tags:["shahed"]}}' for x, z in [(5,0),(2.5,4.3),(-2.5,4.3),(-5,0),(-2.5,-4.3),(2.5,-4.3)]))
wr("bfx/extra_blast", """
$summon minecraft:creeper ~$(x) ~$(y) ~$(z) {Fuse:0s,ignited:1b,ExplosionRadius:12b,Silent:1b,NoAI:1b,Invulnerable:1b,CustomName:'"Бетонобойная бомба"',Tags:["shahed"]}
""")
wr("bfx/rubble", """
# что уцелело от ослабленной зоны — дроблёная порода; щебень под сводом осыпается в полость
fill ~-11 ~1 ~-11 ~11 ~11 ~11 minecraft:gravel replace minecraft:ice
fill ~-11 ~-11 ~-11 ~11 ~0 ~11 minecraft:cobblestone replace minecraft:ice
data modify storage shahed:tmp br set value {rx:5,ry:1,rz:5,oy:-7,rmin:1,rmax:2,blk:"minecraft:magma_block",rep:"#shahed:bb_rock"}
scoreboard players set #bn shahed 3
function shahed:bfx/blobs
""")
s = rd("bfx/tick")
if "bfx/rubble" not in s:
    s = s.replace("execute if score @s shahed_t matches 3 run function shahed:bfx/secondary\n",
                  "execute if score @s shahed_t matches 2 run function shahed:bfx/rubble\nexecute if score @s shahed_t matches 3 run function shahed:bfx/secondary\n")
    wr("bfx/tick", s)
s = rd("bfx/start")
if "damage" not in s:
    s = s.replace("function shahed:bfx/lights_on\n", """function shahed:bfx/lights_on
# ударная волна в породе: достаёт и за камнем, без прямой видимости
execute as @e[distance=..10,tag=!shahed,type=!minecraft:marker] run damage @s 200 minecraft:explosion
execute as @e[distance=10..16,tag=!shahed,type=!minecraft:marker] run damage @s 40 minecraft:explosion
execute as @e[distance=16..24,tag=!shahed,type=!minecraft:marker] run damage @s 12 minecraft:explosion
execute as @a[distance=24..34] run damage @s 4 minecraft:explosion
""")
    wr("bfx/start", s)

# ---------- провал грунта: неровная «труба» и рваная воронка ----------
wr("bfx/collapse", """
# «труба» обрушения из неровных комьев щебня от свода полости к поверхности
data modify storage shahed:tmp br set value {rx:1,ry:1,rz:1,oy:0,rmin:1,rmax:2,blk:"minecraft:gravel",rep:"#shahed:drillable"}
scoreboard players operation #cn shahed = @s shahed_E
scoreboard players remove #cn shahed 8
scoreboard players operation #cn shahed /= #3 shahed
scoreboard players set #ci shahed 1
function shahed:bfx/chimney
# рваная воронка провала на поверхности
data modify storage shahed:tmp br set value {rx:7,ry:1,rz:7,oy:-2,rmin:1,rmax:3,blk:"minecraft:gravel",rep:"#shahed:bb_soil"}
scoreboard players set #bn shahed 10
function shahed:bfx/blobs
data modify storage shahed:tmp br set value {rx:8,ry:1,rz:8,oy:-1,rmin:1,rmax:2,blk:"minecraft:coarse_dirt",rep:"#shahed:bb_soil"}
scoreboard players set #bn shahed 5
function shahed:bfx/blobs
data modify storage shahed:tmp br set value {rx:6,ry:1,rz:6,oy:-1,rmin:1,rmax:2,blk:"minecraft:rooted_dirt",rep:"#shahed:bb_soil"}
scoreboard players set #bn shahed 3
function shahed:bfx/blobs
playsound minecraft:block.gravel.break block @a ~ ~ ~ 8 0.5
playsound minecraft:block.gravel.break block @a ~ ~ ~ 8 0.7
playsound shahed:bb.quake master @a[distance=..120,scores={shahed_mode=2..}] ~ ~ ~ 8 0.8 0.4
playsound minecraft:entity.warden.emerge master @a[distance=..120,scores={shahed_mode=..1}] ~ ~ ~ 8 0.6 0.4
particle minecraft:campfire_cosy_smoke ~ ~1 ~ 4 1 4 0.02 60 force @a
execute as @a[distance=..50] unless score @s shahed_quake matches 30.. run scoreboard players set @s shahed_quake 30
""")
wr("bfx/chimney", """
execute if score #ci shahed > #cn shahed run return 0
scoreboard players operation #oyv shahed = #ci shahed
scoreboard players operation #oyv shahed *= #-3 shahed
execute store result storage shahed:tmp br.oy int 1 run scoreboard players get #oyv shahed
function shahed:bfx/blob_rand with storage shahed:tmp br
scoreboard players add #ci shahed 1
function shahed:bfx/chimney
""")
s = rd("bfx/surf_tick").replace("run function shahed:bfx/collapse with entity @s data", "run function shahed:bfx/collapse")
wr("bfx/surf_tick", s)
t = rd("load")
if "#-3 shahed" not in t:
    wr("load", t + "scoreboard players set #-3 shahed -3\n")
print("ok")
