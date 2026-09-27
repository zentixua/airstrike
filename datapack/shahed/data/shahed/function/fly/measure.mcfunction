# расстояния до цели: дециблоки (×10) для дальности, сантиблоки (×100) для высоты
execute store result score #x shahed run data get entity @s Pos[0] 10
execute store result score #y shahed run data get entity @s Pos[1] 10
execute store result score #z shahed run data get entity @s Pos[2] 10
execute store result score #ycb shahed run data get entity @s Pos[1] 100
scoreboard players operation #dx shahed = @s shahed_tx
scoreboard players operation #dx shahed -= #x shahed
scoreboard players operation #dy shahed = @s shahed_ty
scoreboard players operation #dy shahed -= #y shahed
scoreboard players operation #dz shahed = @s shahed_tz
scoreboard players operation #dz shahed -= #z shahed
# дальше 2.5 км — зажимаем, чтобы квадраты не переполнили int
execute if score #dx shahed matches 25001.. run scoreboard players set #dx shahed 25000
execute if score #dx shahed matches ..-25001 run scoreboard players set #dx shahed -25000
execute if score #dy shahed matches 25001.. run scoreboard players set #dy shahed 25000
execute if score #dy shahed matches ..-25001 run scoreboard players set #dy shahed -25000
execute if score #dz shahed matches 25001.. run scoreboard players set #dz shahed 25000
execute if score #dz shahed matches ..-25001 run scoreboard players set #dz shahed -25000
scoreboard players operation #hd2 shahed = #dx shahed
scoreboard players operation #hd2 shahed *= #dx shahed
scoreboard players operation #t2 shahed = #dz shahed
scoreboard players operation #t2 shahed *= #dz shahed
scoreboard players operation #hd2 shahed += #t2 shahed
scoreboard players operation #d2 shahed = #dy shahed
scoreboard players operation #d2 shahed *= #dy shahed
scoreboard players operation #d2 shahed += #hd2 shahed
# √ методом Ньютона от завышенного начального приближения |dx|+|dy|+|dz|
scoreboard players operation #s shahed = #dx shahed
execute if score #s shahed matches ..-1 run scoreboard players operation #s shahed *= #-1 shahed
scoreboard players operation #t2 shahed = #dy shahed
execute if score #t2 shahed matches ..-1 run scoreboard players operation #t2 shahed *= #-1 shahed
scoreboard players operation #s shahed += #t2 shahed
scoreboard players operation #t2 shahed = #dz shahed
execute if score #t2 shahed matches ..-1 run scoreboard players operation #t2 shahed *= #-1 shahed
scoreboard players operation #s shahed += #t2 shahed
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
scoreboard players operation #d shahed = #s shahed
