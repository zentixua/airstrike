$execute unless entity @a[name="$(name)"] run return run function shahed:ui/notfound {name:"$(name)",cmd:"shahed:menu/fire_at"}
function shahed:menu/prep
data remove storage shahed:tmp sv.sl
$data modify storage shahed:tmp sv.who set value "$(name)"
$execute at @a[name="$(name)",limit=1] rotated as @s run function shahed:salvo/core
$tellraw @s ["",{"text":"   Цель: ","color":"gray"},{"text":"$(name)","color":"gold"}]
playsound minecraft:block.note_block.bass master @s ~ ~ ~ 1 0.5
