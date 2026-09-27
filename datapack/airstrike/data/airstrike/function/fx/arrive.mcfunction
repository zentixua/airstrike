# пришёл фронт взрыва: мотор/свист обрывается, бьёт по груди
stopsound @s ambient
execute if entity @s[scores={airstrike_mode=1..}] if score #band airstrike matches 1..6 run playsound airstrike:blast.sub master @s ~ ~ ~ 1 1 1
execute if entity @s[scores={airstrike_mode=1..}] if score #band airstrike matches 4.. run playsound airstrike:blast.far master @s ~ ~ ~ 1 1 1
execute if score #band airstrike matches 1..3 run function airstrike:fx/snd_close
execute if score #band airstrike matches 4..9 run function airstrike:fx/snd_mid
execute if score #band airstrike matches 10.. run function airstrike:fx/snd_far
execute if data storage airstrike:cfg {shake:1b} run function airstrike:fx/set_shake
execute if score #band airstrike matches 1..2 run function airstrike:fx/push
