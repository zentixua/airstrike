$tag @e[tag=shahed_te$(n)] remove shahed_te$(n)
$scoreboard players set #u shahed $(n)
scoreboard players remove #u shahed 1
execute if score #u shahed matches ..0 run return 0
scoreboard players operation #lim shahed = #tn shahed
scoreboard players remove #lim shahed 256
execute if score #u shahed <= #lim shahed run return 0
execute store result storage shahed:tmp ua.n int 1 run scoreboard players get #u shahed
function shahed:untag_loop with storage shahed:tmp ua
