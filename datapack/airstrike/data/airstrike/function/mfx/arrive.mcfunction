# пришёл фронт взрыва: мотор/свист обрывается, бьёт по груди
stopsound @s ambient
execute if entity @s[scores={airstrike_mode=1..}] if score #band airstrike matches 1..8 run playsound airstrike:blast.sub master @s ~ ~ ~ 1 0.85 1
execute if entity @s[scores={airstrike_mode=1..}] if score #band airstrike matches 4.. run playsound airstrike:blast.far master @s ~ ~ ~ 1 0.85 1
execute if score #band airstrike matches 1..3 run function airstrike:mfx/snd_close
execute if score #band airstrike matches 4..10 run function airstrike:mfx/snd_mid
execute if score #band airstrike matches 11.. run function airstrike:mfx/snd_far
execute if data storage airstrike:cfg {shake:1b} run function airstrike:mfx/set_shake
execute if score #band airstrike matches 1..3 run function airstrike:mfx/push
