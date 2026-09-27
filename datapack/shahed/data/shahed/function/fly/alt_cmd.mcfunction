# вход: #H — желаемая высота (сантиблоки); плавный фильтр высоты и команда угловой скорости по тангажу
scoreboard players operation #t2 shahed = #H shahed
scoreboard players operation #t2 shahed -= @s shahed_h
scoreboard players operation #t2 shahed /= #5 shahed
scoreboard players operation @s shahed_h += #t2 shahed
scoreboard players operation #g shahed = @s shahed_h
scoreboard players operation #g shahed -= #ycb shahed
scoreboard players operation #g shahed *= #12 shahed
scoreboard players operation #g shahed /= #10 shahed
execute if score #g shahed matches 1501.. run scoreboard players set #g shahed 1500
execute if score #g shahed matches ..-1201 run scoreboard players set #g shahed -1200
scoreboard players operation #thd shahed = #g shahed
scoreboard players operation #thd shahed *= #-1 shahed
