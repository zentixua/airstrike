tag @s remove airstrike_newfx
scoreboard players set @s airstrike_t 0
function airstrike:debris/sample
function airstrike:mfx/flash
function airstrike:cfg_clamp
function airstrike:mfx/main_blast with storage airstrike:cfg
function airstrike:mfx/mod_burst
particle minecraft:flash ~ ~14 ~ 0 0 0 0 1 force @a
particle minecraft:flash ~ ~6 ~ 3 3 3 0 4 force @a
