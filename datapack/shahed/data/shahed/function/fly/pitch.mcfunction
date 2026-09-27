# #thd или #wcmd → плавный тангаж: |ω| ≤ #W, |dω| ≤ #A
scoreboard players operation #nW shahed = #W shahed
scoreboard players operation #nW shahed *= #-1 shahed
scoreboard players operation #wcmd shahed < #W shahed
scoreboard players operation #wcmd shahed > #nW shahed
scoreboard players operation #dw shahed = #wcmd shahed
scoreboard players operation #dw shahed -= @s shahed_wp
scoreboard players operation #nA shahed = #A shahed
scoreboard players operation #nA shahed *= #-1 shahed
scoreboard players operation #dw shahed < #A shahed
scoreboard players operation #dw shahed > #nA shahed
scoreboard players operation @s shahed_wp += #dw shahed
scoreboard players operation #cp shahed += @s shahed_wp
execute if score #cp shahed matches 8901.. run scoreboard players set #cp shahed 8900
execute if score #cp shahed matches ..-8901 run scoreboard players set #cp shahed -8900
execute store result entity @s Rotation[1] float 0.01 run scoreboard players get #cp shahed
