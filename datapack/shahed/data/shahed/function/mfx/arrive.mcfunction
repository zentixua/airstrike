# пришёл фронт взрыва: мотор/свист обрывается, бьёт по груди
stopsound @s ambient
execute if entity @s[scores={shahed_mode=1..}] if score #band shahed matches 1..8 run playsound shahed:blast.sub master @s ~ ~ ~ 1 0.85 1
execute if entity @s[scores={shahed_mode=1..}] if score #band shahed matches 4.. run playsound shahed:blast.far master @s ~ ~ ~ 1 0.85 1
execute if score #band shahed matches 1..3 run function shahed:mfx/snd_close
execute if score #band shahed matches 4..10 run function shahed:mfx/snd_mid
execute if score #band shahed matches 11.. run function shahed:mfx/snd_far
execute if data storage shahed:cfg {shake:1b} run function shahed:mfx/set_shake
execute if score #band shahed matches 1..3 run function shahed:mfx/push
