# $(cmd) — функция, которой передаётся {name:"<ник>"}
tag @s add airstrike_viewer
$data modify storage airstrike:tmp pl.cmd set value "$(cmd)"
execute at @s run summon minecraft:armor_stand ~ ~-3 ~ {Tags:["airstrike","airstrike_namer"],Invisible:1b,Marker:1b,NoGravity:1b,Silent:1b}
execute as @a run function airstrike:ui/player_btn
kill @e[type=minecraft:armor_stand,tag=airstrike_namer]
tag @s remove airstrike_viewer
