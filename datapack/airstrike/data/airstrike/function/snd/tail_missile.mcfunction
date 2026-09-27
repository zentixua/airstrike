# звук ещё «летит» к дальним слушателям, пока до них не дошёл фронт взрыва
execute store result score #sx airstrike run data get entity @s Pos[0] 10
execute store result score #sy airstrike run data get entity @s Pos[1] 10
execute store result score #sz airstrike run data get entity @s Pos[2] 10

scoreboard players set #sid airstrike -1
scoreboard players set #kind airstrike 2
scoreboard players set #sph airstrike 2
scoreboard players set #nodop airstrike 1
scoreboard players set #wd airstrike 99999
scoreboard players operation #m6 airstrike = @s airstrike_t
scoreboard players operation #m6 airstrike %= #6 airstrike
scoreboard players operation #m12 airstrike = @s airstrike_t
scoreboard players operation #m12 airstrike %= #12 airstrike
function airstrike:snd/tail_run with storage airstrike:tmp band
