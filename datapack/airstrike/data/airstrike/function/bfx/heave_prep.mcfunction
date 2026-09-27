data modify storage airstrike:tmp hv set from entity @s data.sp
scoreboard players operation #r airstrike = @s airstrike_t
scoreboard players operation #r airstrike *= #2 airstrike
execute store result storage airstrike:tmp hv.rr int 1 run scoreboard players get #r airstrike
function airstrike:bfx/heave with storage airstrike:tmp hv
