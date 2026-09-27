# #thd или #wcmd → плавный тангаж: |ω| ≤ #W, |dω| ≤ #A
scoreboard players operation #nW airstrike = #W airstrike
scoreboard players operation #nW airstrike *= #-1 airstrike
scoreboard players operation #wcmd airstrike < #W airstrike
scoreboard players operation #wcmd airstrike > #nW airstrike
scoreboard players operation #dw airstrike = #wcmd airstrike
scoreboard players operation #dw airstrike -= @s airstrike_wp
scoreboard players operation #nA airstrike = #A airstrike
scoreboard players operation #nA airstrike *= #-1 airstrike
scoreboard players operation #dw airstrike < #A airstrike
scoreboard players operation #dw airstrike > #nA airstrike
scoreboard players operation @s airstrike_wp += #dw airstrike
scoreboard players operation #cp airstrike += @s airstrike_wp
execute if score #cp airstrike matches 8901.. run scoreboard players set #cp airstrike 8900
execute if score #cp airstrike matches ..-8901 run scoreboard players set #cp airstrike -8900
execute store result entity @s Rotation[1] float 0.01 run scoreboard players get #cp airstrike
