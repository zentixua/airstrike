data modify storage shahed:tmp sv2.r set from entity @s data.r
scoreboard players set #try shahed 0
scoreboard players set #dx shahed 0
scoreboard players set #dz shahed 0
execute store result score #r shahed run data get entity @s data.r
execute if score #r shahed matches 1.. run function shahed:salvo/pick with storage shahed:tmp sv2
execute store result storage shahed:tmp sp2.x int 1 run scoreboard players get #dx shahed
execute store result storage shahed:tmp sp2.z int 1 run scoreboard players get #dz shahed
kill @e[type=minecraft:marker,tag=shahed_tgt_new]
data remove storage shahed:tmp who
data modify storage shahed:tmp who set from entity @s data.who
data remove storage shahed:tmp sl
data modify storage shahed:tmp sl set from entity @s data.sl
function shahed:salvo/offset_aim
# шахеды и ракеты бьют по поверхности; бомба — на глубине центра (найдёт пещеру под игроком)
scoreboard players set #air shahed 0
execute if block ~ ~-1 ~ #shahed:passable if block ~ ~-2 ~ #shahed:passable if block ~ ~-3 ~ #shahed:passable run scoreboard players set #air shahed 1
execute unless score @s shahed_ph matches 3 if score #r shahed matches 1.. if score #air shahed matches 0 run function shahed:salvo/place with storage shahed:tmp sp2
execute unless score @s shahed_ph matches 3 if score #r shahed matches 1.. if score #air shahed matches 1 run function shahed:salvo/place_air with storage shahed:tmp sp2
execute unless score @s shahed_ph matches 3 if score #r shahed matches 0 run summon minecraft:marker ~ ~1 ~ {Tags:["shahed","shahed_tgt_new"]}
execute if score @s shahed_ph matches 3 run function shahed:salvo/place_deep with storage shahed:tmp sp2
execute store result score #yb shahed run data get entity @s data.yaw 100
execute store result score #yo shahed run random value -3500..3500
scoreboard players operation #yb shahed += #yo shahed
execute store result entity @s Rotation[0] float 0.01 run scoreboard players get #yb shahed
scoreboard players set #nosiren shahed 1
execute if score @s shahed_ph matches 1 run function shahed:launch_common
execute if score @s shahed_ph matches 2 run function shahed:missile/launch_common
execute if score @s shahed_ph matches 3 run function shahed:bunker/launch_common
scoreboard players set #nosiren shahed 0
data remove storage shahed:tmp who
data remove storage shahed:tmp wo
data remove storage shahed:tmp sl
kill @e[type=minecraft:marker,tag=shahed_tgt_new]
