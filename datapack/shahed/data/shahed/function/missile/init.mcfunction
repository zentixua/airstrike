tag @s remove shahed_new
data modify entity @s data set from storage shahed:tmp d
scoreboard players operation @s shahed_id = #next shahed_id
scoreboard players set @s shahed_ph 9
scoreboard players set @s shahed_t 0
scoreboard players set @s shahed_w 0
scoreboard players set @s shahed_v 1150
execute store result score @s shahed_tx run data get storage shahed:tmp d.tx 10
execute store result score @s shahed_ty run data get storage shahed:tmp d.ty 10
execute store result score @s shahed_tz run data get storage shahed:tmp d.tz 10
scoreboard players set @s shahed_wp 0
scoreboard players set @s shahed_wy 0
# низкий полёт: 12 блоков над рельефом (и не ниже цели+12)
execute store result score #y shahed run data get storage shahed:tmp d.ty 100
scoreboard players add #y shahed 1200
scoreboard players set #gy shahed -9999999
execute positioned over motion_blocking run summon minecraft:marker ~ ~12 ~ {Tags:["shahed","shahed_tmp"]}
execute store result score #gy shahed run data get entity @e[type=minecraft:marker,tag=shahed_tmp,limit=1] Pos[1] 100
kill @e[type=minecraft:marker,tag=shahed_tmp]
scoreboard players operation #y shahed > #gy shahed
execute store result entity @s Pos[1] double 0.01 run scoreboard players get #y shahed
scoreboard players operation @s shahed_h = #y shahed
# горка только если стартуем дальше 185 блоков от цели
function shahed:fly/measure
scoreboard players set @s shahed_pop 0
execute if score #hd2 shahed matches 3422500.. run scoreboard players set @s shahed_pop 1
