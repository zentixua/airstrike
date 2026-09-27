tag @s remove shahed_newfx
scoreboard players set @s shahed_t 0
function shahed:debris/sample
function shahed:mfx/flash
function shahed:cfg_clamp
function shahed:mfx/main_blast with storage shahed:cfg
function shahed:mfx/mod_burst
particle minecraft:flash ~ ~14 ~ 0 0 0 0 1 force @a
particle minecraft:flash ~ ~6 ~ 3 3 3 0 4 force @a
