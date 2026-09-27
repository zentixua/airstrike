particle minecraft:campfire_signal_smoke ~ ~0.5 ~ 0.3 0.3 0.3 0.02 4 force @a
execute if entity @s[scores={airstrike_t=..80}] run particle minecraft:large_smoke ~ ~2 ~ 0.4 1 0.4 0.08 6 force @a
$execute if entity @s[scores={airstrike_t=..90}] run particle minecraft:dust{color:[$(r)f,$(g)f,$(bl)f],scale:3.5f} ~ ~1 ~ 0 1 0 0.4 0 force @a
$execute if entity @s[scores={airstrike_t=..90}] run particle minecraft:dust{color:[$(r)f,$(g)f,$(bl)f],scale:3.5f} ~0.3 ~2 ~-0.2 0 1 0 0.3 0 force @a
