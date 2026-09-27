scoreboard players operation #slot airstrike = #sid airstrike
scoreboard players operation #slot airstrike %= #4 airstrike
execute if score #slot airstrike matches 0 run return run function airstrike:snd/dop0
execute if score #slot airstrike matches 1 run return run function airstrike:snd/dop1
execute if score #slot airstrike matches 2 run return run function airstrike:snd/dop2
function airstrike:snd/dop3
