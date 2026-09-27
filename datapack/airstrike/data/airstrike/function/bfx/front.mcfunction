scoreboard players operation #band airstrike = @s airstrike_t
scoreboard players operation #a airstrike = @s airstrike_t
scoreboard players remove #a airstrike 1
scoreboard players operation #a airstrike *= #1700 airstrike
scoreboard players operation #b airstrike = #a airstrike
scoreboard players add #b airstrike 1699
execute store result storage airstrike:tmp band.a double 0.01 run scoreboard players get #a airstrike
execute store result storage airstrike:tmp band.b double 0.01 run scoreboard players get #b airstrike
function airstrike:bfx/band with storage airstrike:tmp band
