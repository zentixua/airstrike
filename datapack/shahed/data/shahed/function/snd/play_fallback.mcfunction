# без пакета: те же направление, дальность и Доплер, но звуки из модов (раз в 12 тиков — меньше наложений)
execute unless score #m12 shahed matches 0 run return 0
execute store result storage shahed:tmp snd.g double 0.01 run scoreboard players get #g shahed
execute if score #kind shahed matches 1 run function shahed:snd/fb_drone
execute if score #kind shahed matches 2 run function shahed:snd/fb_missile

execute if score #kind shahed matches 3 run function shahed:snd/fb_jet
execute if score #kind shahed matches 4 run function shahed:snd/fb_fall
