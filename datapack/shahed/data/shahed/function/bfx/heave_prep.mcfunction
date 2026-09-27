data modify storage shahed:tmp hv set from entity @s data.sp
scoreboard players operation #r shahed = @s shahed_t
scoreboard players operation #r shahed *= #2 shahed
execute store result storage shahed:tmp hv.rr int 1 run scoreboard players get #r shahed
function shahed:bfx/heave with storage shahed:tmp hv
