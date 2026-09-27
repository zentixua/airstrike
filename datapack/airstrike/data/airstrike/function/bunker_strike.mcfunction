# /function airstrike:bunker_strike {name:"Ник"} — пробивает грунт над игроком и рвётся в его пещере
$execute unless entity @a[name="$(name)"] run return run function airstrike:ui/notfound {name:"$(name)",cmd:"airstrike:bunker_strike"}
kill @e[type=minecraft:marker,tag=airstrike_tgt_new]
$execute at @a[name="$(name)",limit=1] run summon minecraft:marker ~ ~1 ~ {Tags:["airstrike","airstrike_tgt_new"]}
function airstrike:bunker/launch_common
