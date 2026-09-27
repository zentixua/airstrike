# без пакета: те же направление, дальность и Доплер, но звуки из модов (раз в 12 тиков — меньше наложений)
execute unless score #m12 airstrike matches 0 run return 0
execute store result storage airstrike:tmp snd.g double 0.01 run scoreboard players get #g airstrike
execute if score #kind airstrike matches 1 run function airstrike:snd/fb_drone
execute if score #kind airstrike matches 2 run function airstrike:snd/fb_missile

execute if score #kind airstrike matches 3 run function airstrike:snd/fb_jet
execute if score #kind airstrike matches 4 run function airstrike:snd/fb_fall
