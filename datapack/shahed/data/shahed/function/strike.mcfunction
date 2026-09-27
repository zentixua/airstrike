# /function shahed:strike {name:"Ник"}
$execute unless entity @a[name="$(name)"] run return run function shahed:ui/notfound {name:"$(name)",cmd:"shahed:strike"}
kill @e[type=minecraft:marker,tag=shahed_tgt_new]
data remove storage shahed:tmp sl
data remove storage shahed:tmp wo
$data modify storage shahed:tmp who set value "$(name)"
$execute at @a[name="$(name)",limit=1] run summon minecraft:marker ~ ~1 ~ {Tags:["shahed","shahed_tgt_new"]}
function shahed:launch_common
data remove storage shahed:tmp who
