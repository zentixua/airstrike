execute if score #kind airstrike matches 1 run function airstrike:snd/pick_drone
execute if score #kind airstrike matches 2 run function airstrike:snd/pick_missile
execute if score #kind airstrike matches 3 run function airstrike:snd/pick_jet
execute if score #kind airstrike matches 4 run function airstrike:snd/pick_fall
execute store result storage airstrike:tmp snd.p double 0.01 run scoreboard players get #p airstrike
execute store result storage airstrike:tmp snd.g double 0.01 run scoreboard players get #g airstrike
function airstrike:snd/play with storage airstrike:tmp snd
