# вход: #H — желаемая высота (сантиблоки); плавный фильтр высоты и команда угловой скорости по тангажу
scoreboard players operation #t2 airstrike = #H airstrike
scoreboard players operation #t2 airstrike -= @s airstrike_h
scoreboard players operation #t2 airstrike /= #5 airstrike
scoreboard players operation @s airstrike_h += #t2 airstrike
scoreboard players operation #g airstrike = @s airstrike_h
scoreboard players operation #g airstrike -= #ycb airstrike
scoreboard players operation #g airstrike *= #12 airstrike
scoreboard players operation #g airstrike /= #10 airstrike
execute if score #g airstrike matches 1501.. run scoreboard players set #g airstrike 1500
execute if score #g airstrike matches ..-1201 run scoreboard players set #g airstrike -1200
scoreboard players operation #thd airstrike = #g airstrike
scoreboard players operation #thd airstrike *= #-1 airstrike
