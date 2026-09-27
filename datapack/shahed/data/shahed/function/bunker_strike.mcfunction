# /function shahed:bunker_strike {name:"Ник"} — пробивает грунт над игроком и рвётся в его пещере
$execute unless entity @a[name="$(name)"] run return run function shahed:ui/notfound {name:"$(name)",cmd:"shahed:bunker_strike"}
kill @e[type=minecraft:marker,tag=shahed_tgt_new]
$execute at @a[name="$(name)",limit=1] run summon minecraft:marker ~ ~1 ~ {Tags:["shahed","shahed_tgt_new"]}
function shahed:bunker/launch_common
