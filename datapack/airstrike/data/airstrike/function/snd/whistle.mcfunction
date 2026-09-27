# чем ближе ракета к цели, тем ниже тон; громкость — по расстоянию до слушателя; направление — на ракету
scoreboard players operation #wp airstrike = #wd airstrike
scoreboard players operation #wp airstrike *= #140 airstrike
scoreboard players set #k airstrike 2600
scoreboard players operation #wp airstrike /= #k airstrike
scoreboard players add #wp airstrike 60
execute if score #wp airstrike matches 201.. run scoreboard players set #wp airstrike 200
scoreboard players set #wg airstrike 110000
scoreboard players operation #wg airstrike /= #ld airstrike
execute if score #wg airstrike matches ..19 run scoreboard players set #wg airstrike 20
execute if score #wg airstrike matches 101.. run scoreboard players set #wg airstrike 100
execute store result storage airstrike:tmp snd.wp double 0.01 run scoreboard players get #wp airstrike
execute store result storage airstrike:tmp snd.wg double 0.01 run scoreboard players get #wg airstrike
stopsound @s ambient snassets:weapons/bomb_whistle
scoreboard players operation @s airstrike_wsid = #sid airstrike
function airstrike:snd/whistle_m with storage airstrike:tmp snd
