scoreboard players operation #r airstrike = @s airstrike_t
scoreboard players operation #r airstrike *= #32 airstrike
execute store result storage airstrike:tmp ring.r double 0.1 run scoreboard players get #r airstrike
function airstrike:fx/ring with storage airstrike:tmp ring
