$tag @e[tag=airstrike_te$(n)] remove airstrike_te$(n)
$scoreboard players set #u airstrike $(n)
scoreboard players remove #u airstrike 1
execute if score #u airstrike matches ..0 run return 0
scoreboard players operation #lim airstrike = #tn airstrike
scoreboard players remove #lim airstrike 256
execute if score #u airstrike <= #lim airstrike run return 0
execute store result storage airstrike:tmp ua.n int 1 run scoreboard players get #u airstrike
function airstrike:untag_loop with storage airstrike:tmp ua
