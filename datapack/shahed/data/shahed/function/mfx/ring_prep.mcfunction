scoreboard players operation #r shahed = @s shahed_t
scoreboard players operation #r shahed *= #35 shahed
execute store result storage shahed:tmp ring.r double 0.1 run scoreboard players get #r shahed
function shahed:mfx/ring with storage shahed:tmp ring
