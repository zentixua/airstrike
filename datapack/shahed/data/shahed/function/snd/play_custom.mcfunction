execute if score #kind shahed matches 1 run function shahed:snd/pick_drone
execute if score #kind shahed matches 2 run function shahed:snd/pick_missile
execute if score #kind shahed matches 3 run function shahed:snd/pick_jet
execute if score #kind shahed matches 4 run function shahed:snd/pick_fall
execute store result storage shahed:tmp snd.p double 0.01 run scoreboard players get #p shahed
execute store result storage shahed:tmp snd.g double 0.01 run scoreboard players get #g shahed
function shahed:snd/play with storage shahed:tmp snd
