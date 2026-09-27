# цель — игрок: каждый тик его текущая позиция (центр тела); ушёл в другой мир или далеко — держим последнюю точку
$execute unless entity @a[name="$(who)",distance=..3000] run return 0
$scoreboard players operation #t airstrike = @a[name="$(who)",distance=..3000,limit=1] airstrike_px
scoreboard players operation #t airstrike *= #10 airstrike
execute store result score #o airstrike run data get entity @s data.wo.x
scoreboard players operation #t airstrike += #o airstrike
execute store result entity @s data.tx double 0.01 run scoreboard players get #t airstrike
$scoreboard players operation #t airstrike = @a[name="$(who)",distance=..3000,limit=1] airstrike_py
scoreboard players operation #t airstrike *= #10 airstrike
scoreboard players add #t airstrike 100
execute store result entity @s data.ty double 0.01 run scoreboard players get #t airstrike
$scoreboard players operation #t airstrike = @a[name="$(who)",distance=..3000,limit=1] airstrike_pz
scoreboard players operation #t airstrike *= #10 airstrike
execute store result score #o airstrike run data get entity @s data.wo.z
scoreboard players operation #t airstrike += #o airstrike
execute store result entity @s data.tz double 0.01 run scoreboard players get #t airstrike
function airstrike:track_scores
