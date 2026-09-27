# цель — игрок: каждый тик его текущая позиция (центр тела); ушёл в другой мир или далеко — держим последнюю точку
$execute unless entity @a[name="$(who)",distance=..3000] run return 0
$scoreboard players operation #t shahed = @a[name="$(who)",distance=..3000,limit=1] shahed_px
scoreboard players operation #t shahed *= #10 shahed
execute store result score #o shahed run data get entity @s data.wo.x
scoreboard players operation #t shahed += #o shahed
execute store result entity @s data.tx double 0.01 run scoreboard players get #t shahed
$scoreboard players operation #t shahed = @a[name="$(who)",distance=..3000,limit=1] shahed_py
scoreboard players operation #t shahed *= #10 shahed
scoreboard players add #t shahed 100
execute store result entity @s data.ty double 0.01 run scoreboard players get #t shahed
$scoreboard players operation #t shahed = @a[name="$(who)",distance=..3000,limit=1] shahed_pz
scoreboard players operation #t shahed *= #10 shahed
execute store result score #o shahed run data get entity @s data.wo.z
scoreboard players operation #t shahed += #o shahed
execute store result entity @s data.tz double 0.01 run scoreboard players get #t shahed
function shahed:track_scores
