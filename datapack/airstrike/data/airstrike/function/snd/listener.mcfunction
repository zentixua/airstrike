# расстояние от уха слушателя до источника (дециблоки)
scoreboard players operation #lx airstrike = @s airstrike_px
scoreboard players operation #ly airstrike = @s airstrike_py
scoreboard players add #ly airstrike 16
scoreboard players operation #lz airstrike = @s airstrike_pz
scoreboard players operation #ex airstrike = #sx airstrike
scoreboard players operation #ex airstrike -= #lx airstrike
scoreboard players operation #ey airstrike = #sy airstrike
scoreboard players operation #ey airstrike -= #ly airstrike
scoreboard players operation #ez airstrike = #sz airstrike
scoreboard players operation #ez airstrike -= #lz airstrike
scoreboard players operation #d2 airstrike = #ex airstrike
scoreboard players operation #d2 airstrike *= #ex airstrike
scoreboard players operation #q airstrike = #ey airstrike
scoreboard players operation #q airstrike *= #ey airstrike
scoreboard players operation #d2 airstrike += #q airstrike
scoreboard players operation #q airstrike = #ez airstrike
scoreboard players operation #q airstrike *= #ez airstrike
scoreboard players operation #d2 airstrike += #q airstrike
scoreboard players operation #s airstrike = #ex airstrike
execute if score #s airstrike matches ..-1 run scoreboard players operation #s airstrike *= #-1 airstrike
scoreboard players operation #q airstrike = #ey airstrike
execute if score #q airstrike matches ..-1 run scoreboard players operation #q airstrike *= #-1 airstrike
scoreboard players operation #s airstrike += #q airstrike
scoreboard players operation #q airstrike = #ez airstrike
execute if score #q airstrike matches ..-1 run scoreboard players operation #q airstrike *= #-1 airstrike
scoreboard players operation #s airstrike += #q airstrike
execute if score #s airstrike matches ..0 run scoreboard players set #s airstrike 1
scoreboard players operation #q airstrike = #d2 airstrike
scoreboard players operation #q airstrike /= #s airstrike
scoreboard players operation #s airstrike += #q airstrike
scoreboard players operation #s airstrike /= #2 airstrike
execute if score #s airstrike matches ..0 run scoreboard players set #s airstrike 1
scoreboard players operation #q airstrike = #d2 airstrike
scoreboard players operation #q airstrike /= #s airstrike
scoreboard players operation #s airstrike += #q airstrike
scoreboard players operation #s airstrike /= #2 airstrike
execute if score #s airstrike matches ..0 run scoreboard players set #s airstrike 1
scoreboard players operation #q airstrike = #d2 airstrike
scoreboard players operation #q airstrike /= #s airstrike
scoreboard players operation #s airstrike += #q airstrike
scoreboard players operation #s airstrike /= #2 airstrike
execute if score #s airstrike matches ..0 run scoreboard players set #s airstrike 1
scoreboard players operation #q airstrike = #d2 airstrike
scoreboard players operation #q airstrike /= #s airstrike
scoreboard players operation #s airstrike += #q airstrike
scoreboard players operation #s airstrike /= #2 airstrike
execute if score #s airstrike matches ..0 run scoreboard players set #s airstrike 1
scoreboard players operation #q airstrike = #d2 airstrike
scoreboard players operation #q airstrike /= #s airstrike
scoreboard players operation #s airstrike += #q airstrike
scoreboard players operation #s airstrike /= #2 airstrike
execute if score #s airstrike matches ..0 run scoreboard players set #s airstrike 1
scoreboard players operation #q airstrike = #d2 airstrike
scoreboard players operation #q airstrike /= #s airstrike
scoreboard players operation #s airstrike += #q airstrike
scoreboard players operation #s airstrike /= #2 airstrike
execute if score #s airstrike matches ..0 run scoreboard players set #s airstrike 1
scoreboard players operation #q airstrike = #d2 airstrike
scoreboard players operation #q airstrike /= #s airstrike
scoreboard players operation #s airstrike += #q airstrike
scoreboard players operation #s airstrike /= #2 airstrike
execute if score #s airstrike matches ..0 run scoreboard players set #s airstrike 1
scoreboard players operation #q airstrike = #d2 airstrike
scoreboard players operation #q airstrike /= #s airstrike
scoreboard players operation #s airstrike += #q airstrike
scoreboard players operation #s airstrike /= #2 airstrike
execute if score #s airstrike matches ..0 run scoreboard players set #s airstrike 1
scoreboard players operation #ld airstrike = #s airstrike
# точка звука: 3 блока от уха в сторону источника (без поиска сущностей)
scoreboard players operation #ox airstrike = #ex airstrike
scoreboard players operation #ox airstrike *= #300 airstrike
scoreboard players operation #ox airstrike /= #ld airstrike
scoreboard players operation #oy airstrike = #ey airstrike
scoreboard players operation #oy airstrike *= #300 airstrike
scoreboard players operation #oy airstrike /= #ld airstrike
scoreboard players add #oy airstrike 160
scoreboard players operation #oz airstrike = #ez airstrike
scoreboard players operation #oz airstrike *= #300 airstrike
scoreboard players operation #oz airstrike /= #ld airstrike
execute store result storage airstrike:tmp snd.x double 0.01 run scoreboard players get #ox airstrike
execute store result storage airstrike:tmp snd.y double 0.01 run scoreboard players get #oy airstrike
execute store result storage airstrike:tmp snd.z double 0.01 run scoreboard players get #oz airstrike
# Доплер: скорость сближения за 3 тика; у слушателя 4 ячейки памяти — соседние цели в залпе не сбивают друг друга
scoreboard players set #vr airstrike 0
execute if score #nodop airstrike matches 0 run function airstrike:snd/dop
# скорость звука 343 м/с = 51.5 блока за 3 тика
scoreboard players set #C airstrike 515
scoreboard players set #p airstrike 100
execute if score #kind airstrike matches 1 if score #sph airstrike matches 1 run scoreboard players set #p airstrike 112
scoreboard players operation #den airstrike = #C airstrike
scoreboard players operation #den airstrike += #vr airstrike
execute if score #den airstrike matches ..60 run scoreboard players set #den airstrike 60
scoreboard players operation #p airstrike *= #C airstrike
scoreboard players operation #p airstrike /= #den airstrike
execute if score #p airstrike matches ..49 run scoreboard players set #p airstrike 50
execute if score #p airstrike matches 201.. run scoreboard players set #p airstrike 200
# громкость ~ 1/расстояние: шахед на полную с 60 блоков, ракета с 90, B-2 со 120, бомба с 40
execute if score #kind airstrike matches 1 run scoreboard players set #g airstrike 60000
execute if score #kind airstrike matches 2 run scoreboard players set #g airstrike 90000
execute if score #kind airstrike matches 3 run scoreboard players set #g airstrike 120000
execute if score #kind airstrike matches 4 run scoreboard players set #g airstrike 40000
scoreboard players operation #g airstrike /= #ld airstrike
execute if score #g airstrike matches ..11 run scoreboard players set #g airstrike 12
execute if score #g airstrike matches 101.. run scoreboard players set #g airstrike 100
# тембр по дальности: рядом — полный, дальше воздух «съедает» верха
scoreboard players set #var airstrike 0
execute if score #ld airstrike matches 450.. run scoreboard players set #var airstrike 1
execute if score #ld airstrike matches 1300.. run scoreboard players set #var airstrike 2
# свист ракеты на последних 260 блоках — пока она приближается; пролетела мимо — свист обрывается
execute if score #kind airstrike matches 2 if score #wd airstrike matches ..2600 if score #m6 airstrike matches 0 unless score #vr airstrike matches 1.. run function airstrike:snd/whistle
execute if score #kind airstrike matches 2 if score #wd airstrike matches ..2600 if score #vr airstrike matches 1.. if score @s airstrike_wsid = #sid airstrike run stopsound @s ambient snassets:weapons/bomb_whistle
scoreboard players set #cm airstrike 0
execute if score @s airstrike_mode matches 1.. if score #kind airstrike matches ..2 run scoreboard players set #cm airstrike 1
execute if score @s airstrike_mode matches 2.. run scoreboard players set #cm airstrike 1
execute if score #cm airstrike matches 1 run function airstrike:snd/play_custom
execute if score #cm airstrike matches 0 run function airstrike:snd/play_fallback
execute if score #kind airstrike matches 2 if score #nodop airstrike matches 0 if score #ld airstrike matches ..320 unless score @s airstrike_fbid = #sid airstrike run function airstrike:snd/missile_pass
