# у каждого игрока своя память сирены (13 с): не накладываем вторую, но удар в другом месте всё равно слышно
execute store result score #now airstrike run time query gametime
tag @a remove airstrike_sir
execute as @a[distance=..350] run function airstrike:siren_chk
playsound airstrike:siren master @a[tag=airstrike_sir,scores={airstrike_mode=1..}] ~115 ~15 ~80 16 1 0
playsound airstrike:siren master @a[tag=airstrike_sir,scores={airstrike_mode=1..}] ~-130 ~15 ~-70 16 0.97 0
playsound snassets:signal/siren master @a[tag=airstrike_sir,scores={airstrike_mode=0}] ~ ~ ~ 0.3 1 0.3
title @a[distance=..350] actionbar {"text":"⚠ ВОЗДУШНАЯ ТРЕВОГА ⚠","color":"red","bold":true}
tag @a remove airstrike_sir
