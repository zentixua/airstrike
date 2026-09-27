# расстояния до цели: дециблоки (×10) для дальности, сантиблоки (×100) для высоты
execute store result score #x airstrike run data get entity @s Pos[0] 10
execute store result score #y airstrike run data get entity @s Pos[1] 10
execute store result score #z airstrike run data get entity @s Pos[2] 10
execute store result score #ycb airstrike run data get entity @s Pos[1] 100
scoreboard players operation #dx airstrike = @s airstrike_tx
scoreboard players operation #dx airstrike -= #x airstrike
scoreboard players operation #dy airstrike = @s airstrike_ty
scoreboard players operation #dy airstrike -= #y airstrike
scoreboard players operation #dz airstrike = @s airstrike_tz
scoreboard players operation #dz airstrike -= #z airstrike
# дальше 2.5 км — зажимаем, чтобы квадраты не переполнили int
execute if score #dx airstrike matches 25001.. run scoreboard players set #dx airstrike 25000
execute if score #dx airstrike matches ..-25001 run scoreboard players set #dx airstrike -25000
execute if score #dy airstrike matches 25001.. run scoreboard players set #dy airstrike 25000
execute if score #dy airstrike matches ..-25001 run scoreboard players set #dy airstrike -25000
execute if score #dz airstrike matches 25001.. run scoreboard players set #dz airstrike 25000
execute if score #dz airstrike matches ..-25001 run scoreboard players set #dz airstrike -25000
scoreboard players operation #hd2 airstrike = #dx airstrike
scoreboard players operation #hd2 airstrike *= #dx airstrike
scoreboard players operation #t2 airstrike = #dz airstrike
scoreboard players operation #t2 airstrike *= #dz airstrike
scoreboard players operation #hd2 airstrike += #t2 airstrike
scoreboard players operation #d2 airstrike = #dy airstrike
scoreboard players operation #d2 airstrike *= #dy airstrike
scoreboard players operation #d2 airstrike += #hd2 airstrike
# √ методом Ньютона от завышенного начального приближения |dx|+|dy|+|dz|
scoreboard players operation #s airstrike = #dx airstrike
execute if score #s airstrike matches ..-1 run scoreboard players operation #s airstrike *= #-1 airstrike
scoreboard players operation #t2 airstrike = #dy airstrike
execute if score #t2 airstrike matches ..-1 run scoreboard players operation #t2 airstrike *= #-1 airstrike
scoreboard players operation #s airstrike += #t2 airstrike
scoreboard players operation #t2 airstrike = #dz airstrike
execute if score #t2 airstrike matches ..-1 run scoreboard players operation #t2 airstrike *= #-1 airstrike
scoreboard players operation #s airstrike += #t2 airstrike
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
scoreboard players operation #d airstrike = #s airstrike
