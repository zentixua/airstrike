scoreboard players operation #slot shahed = #sid shahed
scoreboard players operation #slot shahed %= #4 shahed
execute if score #slot shahed matches 0 run return run function shahed:snd/dop0
execute if score #slot shahed matches 1 run return run function shahed:snd/dop1
execute if score #slot shahed matches 2 run return run function shahed:snd/dop2
function shahed:snd/dop3
