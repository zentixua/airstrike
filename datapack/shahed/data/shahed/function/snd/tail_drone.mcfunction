# звук ещё «летит» к дальним слушателям, пока до них не дошёл фронт взрыва
execute store result score #sx shahed run data get entity @s Pos[0] 10
execute store result score #sy shahed run data get entity @s Pos[1] 10
execute store result score #sz shahed run data get entity @s Pos[2] 10

scoreboard players set #sid shahed -1
scoreboard players set #kind shahed 1
scoreboard players set #sph shahed 1
scoreboard players set #nodop shahed 1
scoreboard players set #wd shahed 99999
scoreboard players operation #m6 shahed = @s shahed_t
scoreboard players operation #m6 shahed %= #6 shahed
scoreboard players operation #m12 shahed = @s shahed_t
scoreboard players operation #m12 shahed %= #12 shahed
function shahed:snd/tail_run with storage shahed:tmp band
