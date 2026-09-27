# чем ближе ракета к цели, тем ниже тон; громкость — по расстоянию до слушателя; направление — на ракету
scoreboard players operation #wp shahed = #wd shahed
scoreboard players operation #wp shahed *= #140 shahed
scoreboard players set #k shahed 2600
scoreboard players operation #wp shahed /= #k shahed
scoreboard players add #wp shahed 60
execute if score #wp shahed matches 201.. run scoreboard players set #wp shahed 200
scoreboard players set #wg shahed 110000
scoreboard players operation #wg shahed /= #ld shahed
execute if score #wg shahed matches ..19 run scoreboard players set #wg shahed 20
execute if score #wg shahed matches 101.. run scoreboard players set #wg shahed 100
execute store result storage shahed:tmp snd.wp double 0.01 run scoreboard players get #wp shahed
execute store result storage shahed:tmp snd.wg double 0.01 run scoreboard players get #wg shahed
stopsound @s ambient snassets:weapons/bomb_whistle
scoreboard players operation @s shahed_wsid = #sid shahed
function shahed:snd/whistle_m with storage shahed:tmp snd
