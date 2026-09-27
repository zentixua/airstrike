tag @s remove airstrike_new
data modify entity @s data set from storage airstrike:tmp d
scoreboard players operation @s airstrike_id = #next airstrike_id
scoreboard players set @s airstrike_ph 9
scoreboard players set @s airstrike_t 0
scoreboard players set @s airstrike_v 1150
execute store result score @s airstrike_tx run data get storage airstrike:tmp d.tx 10
execute store result score @s airstrike_ty run data get storage airstrike:tmp d.ty 10
execute store result score @s airstrike_tz run data get storage airstrike:tmp d.tz 10
scoreboard players set @s airstrike_wp 0
scoreboard players set @s airstrike_wy 0
# низкий полёт: 12 блоков над рельефом (и не ниже цели+12)
execute store result score #y airstrike run data get storage airstrike:tmp d.ty 100
scoreboard players add #y airstrike 1200
scoreboard players set #gy airstrike -9999999
execute positioned over motion_blocking run summon minecraft:marker ~ ~12 ~ {Tags:["airstrike","airstrike_tmp"]}
execute store result score #gy airstrike run data get entity @e[type=minecraft:marker,tag=airstrike_tmp,limit=1] Pos[1] 100
kill @e[type=minecraft:marker,tag=airstrike_tmp]
scoreboard players operation #y airstrike > #gy airstrike
execute store result entity @s Pos[1] double 0.01 run scoreboard players get #y airstrike
scoreboard players operation @s airstrike_h = #y airstrike
# горка только если стартуем дальше 185 блоков от цели
function airstrike:fly/measure
scoreboard players set @s airstrike_pop 0
execute if score #hd2 airstrike matches 3422500.. run scoreboard players set @s airstrike_pop 1
