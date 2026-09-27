import os, sys
sys.argv = [sys.argv[0], sys.argv[1]]
exec(open(os.path.join(os.path.dirname(__file__), "patch_review.py"), encoding="utf-8").read().split("# ---------- 1. tick")[0])

# 1. whistle: stop only this listener's own whistle source when that missile recedes
sub("snd/whistle", "stopsound @s ambient snassets:weapons/bomb_whistle\n",
    "stopsound @s ambient snassets:weapons/bomb_whistle\nscoreboard players operation @s shahed_wsid = #sid shahed\n")
sub("snd/listener", "if score #vr shahed matches 1.. run stopsound @s ambient snassets:weapons/bomb_whistle",
    "if score #vr shahed matches 1.. if score @s shahed_wsid = #sid shahed run stopsound @s ambient snassets:weapons/bomb_whistle")

# 2. siren: per-player 13 s memory instead of one global cooldown
wr("siren_chk", """# этот игрок слышал сирену меньше 13 с назад — вторую поверх не включаем
scoreboard players operation #dt shahed = #now shahed
scoreboard players operation #dt shahed -= @s shahed_sirt
execute if score #dt shahed matches 0..259 run return 0
scoreboard players operation @s shahed_sirt = #now shahed
tag @s add shahed_sir
""")
for p, a, b, msg in [("drone/siren", "~115 ~15 ~80 16 1 0", "~-130 ~15 ~-70 16 0.97 0", "⚠ ВОЗДУШНАЯ ТРЕВОГА ⚠"),
                     ("missile/siren", "~125 ~15 ~-65 16 1 0", "~-95 ~15 ~115 16 0.97 0", "⚠ РАКЕТНАЯ ОПАСНОСТЬ ⚠")]:
    s = rd(p)
    assert a in s and b in s, p
    wr(p, """# у каждого игрока своя память сирены (13 с): не накладываем вторую, но удар в другом месте всё равно слышно
execute store result score #now shahed run time query gametime
tag @a remove shahed_sir
execute as @a[distance=..350] run function shahed:siren_chk
playsound shahed:siren master @a[tag=shahed_sir,scores={shahed_mode=1..}] %s
playsound shahed:siren master @a[tag=shahed_sir,scores={shahed_mode=1..}] %s
playsound snassets:signal/siren master @a[tag=shahed_sir,scores={shahed_mode=0}] ~ ~ ~ 0.3 1 0.3
title @a[distance=..350] actionbar {"text":"%s","color":"red","bold":true}
tag @a remove shahed_sir
""" % (a, b, msg))
sub("clear", "scoreboard players set #siren_t shahed -100000\n", "scoreboard players reset * shahed_sirt\n")
s = rd("load")
s = s.replace("execute unless score #siren_t shahed = #siren_t shahed run scoreboard players set #siren_t shahed -100000\n", "")
# 3. things already in flight during /reload keep sound + tracking
s += """scoreboard objectives add shahed_wsid dummy
scoreboard objectives add shahed_sirt dummy
# то, что уже летит во время /reload, продолжает звучать и следить за целью
tag @e[type=minecraft:marker,tag=shahed_root] add shahed_snd
tag @e[type=minecraft:marker,tag=shahed_fx,scores={shahed_t=..18}] add shahed_snd
tag @e[type=minecraft:marker,tag=shahed_mfx,scores={shahed_t=..18}] add shahed_snd
"""
wr("load", s)

# 4. weakening material nobody builds with (invisible, lives 2 ticks until rubble pass)
sub("bfx/cavity", 'blk:"minecraft:black_stained_glass"', 'blk:"minecraft:structure_void"')
sub("bfx/rubble", "minecraft:gravel replace minecraft:black_stained_glass", "minecraft:gravel replace minecraft:structure_void")
sub("bfx/rubble", "minecraft:cobblestone replace minecraft:black_stained_glass", "minecraft:cobblestone replace minecraft:structure_void")

# 5. fallback: bomb gets its own falling whistle (not the jet's elytra loop); long samples are stopped at the end
s = rd("snd/fb_fall")
s = s.replace("scoreboard players set #k shahed 150", "scoreboard players set #k shahed 100")
s = s.replace('data modify storage shahed:tmp snd.e set value "minecraft:item.elytra.flying"\nstopsound @s ambient minecraft:item.elytra.flying',
              'data modify storage shahed:tmp snd.e set value "snassets:weapons/bomb_whistle"\nstopsound @s ambient snassets:weapons/bomb_whistle')
assert "bomb_whistle" in s and "elytra" not in s
wr("snd/fb_fall", s)
sub("bfx/impact", "stopsound @a[distance=..60] ambient\n",
    "stopsound @a[distance=..60] ambient\nstopsound @a[distance=..400] ambient snassets:weapons/bomb_whistle\n")
sub("bunker/bomber_gone", "shahed_id run kill @s\nkill @s\n", "shahed_id run kill @s\nstopsound @a[scores={shahed_mode=..1}] ambient minecraft:item.elytra.flying\nkill @s\n")
print("patch_review2 OK")
