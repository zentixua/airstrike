# пришёл фронт взрыва: мотор/свист обрывается, бьёт по груди
stopsound @s ambient
execute if entity @s[scores={shahed_mode=1..}] if score #band shahed matches 1..6 run playsound shahed:blast.sub master @s ~ ~ ~ 1 1 1
execute if entity @s[scores={shahed_mode=1..}] if score #band shahed matches 4.. run playsound shahed:blast.far master @s ~ ~ ~ 1 1 1
execute if score #band shahed matches 1..3 run function shahed:fx/snd_close
execute if score #band shahed matches 4..9 run function shahed:fx/snd_mid
execute if score #band shahed matches 10.. run function shahed:fx/snd_far
execute if data storage shahed:cfg {shake:1b} run function shahed:fx/set_shake
execute if score #band shahed matches 1..2 run function shahed:fx/push
