$execute unless entity @a[name="$(name)"] run return run function airstrike:ui/notfound {name:"$(name)",cmd:"airstrike:menu/fire_at"}
function airstrike:menu/prep
data remove storage airstrike:tmp sv.sl
$data modify storage airstrike:tmp sv.who set value "$(name)"
$execute at @a[name="$(name)",limit=1] rotated as @s run function airstrike:salvo/core
$tellraw @s ["",{"text":"   Цель: ","color":"gray"},{"text":"$(name)","color":"gold"}]
playsound minecraft:block.note_block.bass master @s ~ ~ ~ 1 0.5
