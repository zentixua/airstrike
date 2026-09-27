# у каждого игрока своя память сирены (13 с): не накладываем вторую, но удар в другом месте всё равно слышно
execute store result score #now shahed run time query gametime
tag @a remove shahed_sir
execute as @a[distance=..350] run function shahed:siren_chk
playsound shahed:siren master @a[tag=shahed_sir,scores={shahed_mode=1..}] ~125 ~15 ~-65 16 1 0
playsound shahed:siren master @a[tag=shahed_sir,scores={shahed_mode=1..}] ~-95 ~15 ~115 16 0.97 0
playsound snassets:signal/siren master @a[tag=shahed_sir,scores={shahed_mode=0}] ~ ~ ~ 0.3 1 0.3
title @a[distance=..350] actionbar {"text":"⚠ РАКЕТНАЯ ОПАСНОСТЬ ⚠","color":"red","bold":true}
tag @a remove shahed_sir
