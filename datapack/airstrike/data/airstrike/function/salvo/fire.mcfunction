data modify storage airstrike:tmp sv2.r set from entity @s data.r
scoreboard players set #try airstrike 0
scoreboard players set #dx airstrike 0
scoreboard players set #dz airstrike 0
execute store result score #r airstrike run data get entity @s data.r
execute if score #r airstrike matches 1.. run function airstrike:salvo/pick with storage airstrike:tmp sv2
execute store result storage airstrike:tmp sp2.x int 1 run scoreboard players get #dx airstrike
execute store result storage airstrike:tmp sp2.z int 1 run scoreboard players get #dz airstrike
kill @e[type=minecraft:marker,tag=airstrike_tgt_new]
data remove storage airstrike:tmp who
data modify storage airstrike:tmp who set from entity @s data.who
data remove storage airstrike:tmp sl
data modify storage airstrike:tmp sl set from entity @s data.sl
function airstrike:salvo/offset_aim
# шахеды и ракеты бьют по поверхности; бомба — на глубине центра (найдёт пещеру под игроком)
scoreboard players set #air airstrike 0
execute if block ~ ~-1 ~ #airstrike:passable if block ~ ~-2 ~ #airstrike:passable if block ~ ~-3 ~ #airstrike:passable run scoreboard players set #air airstrike 1
execute unless score @s airstrike_ph matches 3 if score #r airstrike matches 1.. if score #air airstrike matches 0 run function airstrike:salvo/place with storage airstrike:tmp sp2
execute unless score @s airstrike_ph matches 3 if score #r airstrike matches 1.. if score #air airstrike matches 1 run function airstrike:salvo/place_air with storage airstrike:tmp sp2
execute unless score @s airstrike_ph matches 3 if score #r airstrike matches 0 run summon minecraft:marker ~ ~1 ~ {Tags:["airstrike","airstrike_tgt_new"]}
execute if score @s airstrike_ph matches 3 run function airstrike:salvo/place_deep with storage airstrike:tmp sp2
execute store result score #yb airstrike run data get entity @s data.yaw 100
execute store result score #yo airstrike run random value -3500..3500
scoreboard players operation #yb airstrike += #yo airstrike
execute store result entity @s Rotation[0] float 0.01 run scoreboard players get #yb airstrike
scoreboard players set #nosiren airstrike 1
execute if score @s airstrike_ph matches 1 run function airstrike:launch_common
execute if score @s airstrike_ph matches 2 run function airstrike:missile/launch_common
execute if score @s airstrike_ph matches 3 run function airstrike:bunker/launch_common
scoreboard players set #nosiren airstrike 0
data remove storage airstrike:tmp who
data remove storage airstrike:tmp wo
data remove storage airstrike:tmp sl
kill @e[type=minecraft:marker,tag=airstrike_tgt_new]
