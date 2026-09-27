# расстояние от уха слушателя до источника (дециблоки)
scoreboard players operation #lx shahed = @s shahed_px
scoreboard players operation #ly shahed = @s shahed_py
scoreboard players add #ly shahed 16
scoreboard players operation #lz shahed = @s shahed_pz
scoreboard players operation #ex shahed = #sx shahed
scoreboard players operation #ex shahed -= #lx shahed
scoreboard players operation #ey shahed = #sy shahed
scoreboard players operation #ey shahed -= #ly shahed
scoreboard players operation #ez shahed = #sz shahed
scoreboard players operation #ez shahed -= #lz shahed
scoreboard players operation #d2 shahed = #ex shahed
scoreboard players operation #d2 shahed *= #ex shahed
scoreboard players operation #q shahed = #ey shahed
scoreboard players operation #q shahed *= #ey shahed
scoreboard players operation #d2 shahed += #q shahed
scoreboard players operation #q shahed = #ez shahed
scoreboard players operation #q shahed *= #ez shahed
scoreboard players operation #d2 shahed += #q shahed
scoreboard players operation #s shahed = #ex shahed
execute if score #s shahed matches ..-1 run scoreboard players operation #s shahed *= #-1 shahed
scoreboard players operation #q shahed = #ey shahed
execute if score #q shahed matches ..-1 run scoreboard players operation #q shahed *= #-1 shahed
scoreboard players operation #s shahed += #q shahed
scoreboard players operation #q shahed = #ez shahed
execute if score #q shahed matches ..-1 run scoreboard players operation #q shahed *= #-1 shahed
scoreboard players operation #s shahed += #q shahed
execute if score #s shahed matches ..0 run scoreboard players set #s shahed 1
scoreboard players operation #q shahed = #d2 shahed
scoreboard players operation #q shahed /= #s shahed
scoreboard players operation #s shahed += #q shahed
scoreboard players operation #s shahed /= #2 shahed
execute if score #s shahed matches ..0 run scoreboard players set #s shahed 1
scoreboard players operation #q shahed = #d2 shahed
scoreboard players operation #q shahed /= #s shahed
scoreboard players operation #s shahed += #q shahed
scoreboard players operation #s shahed /= #2 shahed
execute if score #s shahed matches ..0 run scoreboard players set #s shahed 1
scoreboard players operation #q shahed = #d2 shahed
scoreboard players operation #q shahed /= #s shahed
scoreboard players operation #s shahed += #q shahed
scoreboard players operation #s shahed /= #2 shahed
execute if score #s shahed matches ..0 run scoreboard players set #s shahed 1
scoreboard players operation #q shahed = #d2 shahed
scoreboard players operation #q shahed /= #s shahed
scoreboard players operation #s shahed += #q shahed
scoreboard players operation #s shahed /= #2 shahed
execute if score #s shahed matches ..0 run scoreboard players set #s shahed 1
scoreboard players operation #q shahed = #d2 shahed
scoreboard players operation #q shahed /= #s shahed
scoreboard players operation #s shahed += #q shahed
scoreboard players operation #s shahed /= #2 shahed
execute if score #s shahed matches ..0 run scoreboard players set #s shahed 1
scoreboard players operation #q shahed = #d2 shahed
scoreboard players operation #q shahed /= #s shahed
scoreboard players operation #s shahed += #q shahed
scoreboard players operation #s shahed /= #2 shahed
execute if score #s shahed matches ..0 run scoreboard players set #s shahed 1
scoreboard players operation #q shahed = #d2 shahed
scoreboard players operation #q shahed /= #s shahed
scoreboard players operation #s shahed += #q shahed
scoreboard players operation #s shahed /= #2 shahed
execute if score #s shahed matches ..0 run scoreboard players set #s shahed 1
scoreboard players operation #q shahed = #d2 shahed
scoreboard players operation #q shahed /= #s shahed
scoreboard players operation #s shahed += #q shahed
scoreboard players operation #s shahed /= #2 shahed
execute if score #s shahed matches ..0 run scoreboard players set #s shahed 1
scoreboard players operation #ld shahed = #s shahed
# точка звука: 3 блока от уха в сторону источника (без поиска сущностей)
scoreboard players operation #ox shahed = #ex shahed
scoreboard players operation #ox shahed *= #300 shahed
scoreboard players operation #ox shahed /= #ld shahed
scoreboard players operation #oy shahed = #ey shahed
scoreboard players operation #oy shahed *= #300 shahed
scoreboard players operation #oy shahed /= #ld shahed
scoreboard players add #oy shahed 160
scoreboard players operation #oz shahed = #ez shahed
scoreboard players operation #oz shahed *= #300 shahed
scoreboard players operation #oz shahed /= #ld shahed
execute store result storage shahed:tmp snd.x double 0.01 run scoreboard players get #ox shahed
execute store result storage shahed:tmp snd.y double 0.01 run scoreboard players get #oy shahed
execute store result storage shahed:tmp snd.z double 0.01 run scoreboard players get #oz shahed
# Доплер: скорость сближения за 3 тика; у слушателя 4 ячейки памяти — соседние цели в залпе не сбивают друг друга
scoreboard players set #vr shahed 0
execute if score #nodop shahed matches 0 run function shahed:snd/dop
# скорость звука 343 м/с = 51.5 блока за 3 тика
scoreboard players set #C shahed 515
scoreboard players set #p shahed 100
execute if score #kind shahed matches 1 if score #sph shahed matches 1 run scoreboard players set #p shahed 112
scoreboard players operation #den shahed = #C shahed
scoreboard players operation #den shahed += #vr shahed
execute if score #den shahed matches ..60 run scoreboard players set #den shahed 60
scoreboard players operation #p shahed *= #C shahed
scoreboard players operation #p shahed /= #den shahed
execute if score #p shahed matches ..49 run scoreboard players set #p shahed 50
execute if score #p shahed matches 201.. run scoreboard players set #p shahed 200
# громкость ~ 1/расстояние: шахед на полную с 60 блоков, ракета с 90, B-2 со 120, бомба с 40
execute if score #kind shahed matches 1 run scoreboard players set #g shahed 60000
execute if score #kind shahed matches 2 run scoreboard players set #g shahed 90000
execute if score #kind shahed matches 3 run scoreboard players set #g shahed 120000
execute if score #kind shahed matches 4 run scoreboard players set #g shahed 40000
scoreboard players operation #g shahed /= #ld shahed
execute if score #g shahed matches ..11 run scoreboard players set #g shahed 12
execute if score #g shahed matches 101.. run scoreboard players set #g shahed 100
# тембр по дальности: рядом — полный, дальше воздух «съедает» верха
scoreboard players set #var shahed 0
execute if score #ld shahed matches 450.. run scoreboard players set #var shahed 1
execute if score #ld shahed matches 1300.. run scoreboard players set #var shahed 2
# свист ракеты на последних 260 блоках — пока она приближается; пролетела мимо — свист обрывается
execute if score #kind shahed matches 2 if score #wd shahed matches ..2600 if score #m6 shahed matches 0 unless score #vr shahed matches 1.. run function shahed:snd/whistle
execute if score #kind shahed matches 2 if score #wd shahed matches ..2600 if score #vr shahed matches 1.. if score @s shahed_wsid = #sid shahed run stopsound @s ambient snassets:weapons/bomb_whistle
scoreboard players set #cm shahed 0
execute if score @s shahed_mode matches 1.. if score #kind shahed matches ..2 run scoreboard players set #cm shahed 1
execute if score @s shahed_mode matches 2.. run scoreboard players set #cm shahed 1
execute if score #cm shahed matches 1 run function shahed:snd/play_custom
execute if score #cm shahed matches 0 run function shahed:snd/play_fallback
execute if score #kind shahed matches 2 if score #nodop shahed matches 0 if score #ld shahed matches ..320 unless score @s shahed_fbid = #sid shahed run function shahed:snd/missile_pass
