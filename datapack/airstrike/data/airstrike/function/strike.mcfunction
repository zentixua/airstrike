# /function airstrike:strike {name:"Ник"}
$execute unless entity @a[name="$(name)"] run return run function airstrike:ui/notfound {name:"$(name)",cmd:"airstrike:strike"}
kill @e[type=minecraft:marker,tag=airstrike_tgt_new]
data remove storage airstrike:tmp sl
data remove storage airstrike:tmp wo
$data modify storage airstrike:tmp who set value "$(name)"
$execute at @a[name="$(name)",limit=1] run summon minecraft:marker ~ ~1 ~ {Tags:["airstrike","airstrike_tgt_new"]}
function airstrike:launch_common
data remove storage airstrike:tmp who
