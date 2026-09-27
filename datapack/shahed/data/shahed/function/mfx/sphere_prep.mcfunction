scoreboard players operation #r shahed = @s shahed_t
scoreboard players operation #r shahed *= #45 shahed
execute store result storage shahed:tmp ring.r double 0.1 run scoreboard players get #r shahed
function shahed:mfx/sphere with storage shahed:tmp ring
