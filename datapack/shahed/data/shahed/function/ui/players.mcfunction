# $(cmd) — функция, которой передаётся {name:"<ник>"}
tag @s add shahed_viewer
$data modify storage shahed:tmp pl.cmd set value "$(cmd)"
execute at @s run summon minecraft:armor_stand ~ ~-3 ~ {Tags:["shahed","shahed_namer"],Invisible:1b,Marker:1b,NoGravity:1b,Silent:1b}
execute as @a run function shahed:ui/player_btn
kill @e[type=minecraft:armor_stand,tag=shahed_namer]
tag @s remove shahed_viewer
