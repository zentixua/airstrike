tag @s remove airstrike_new
data modify entity @s data set from storage airstrike:tmp d
scoreboard players operation @s airstrike_id = #next airstrike_id
scoreboard players set @s airstrike_ph 0
scoreboard players set @s airstrike_t 0
scoreboard players set @s airstrike_v 210
execute store result score @s airstrike_tx run data get storage airstrike:tmp d.tx 10
execute store result score @s airstrike_ty run data get storage airstrike:tmp d.ty 10
execute store result score @s airstrike_tz run data get storage airstrike:tmp d.tz 10
scoreboard players set @s airstrike_wp 0
scoreboard players set @s airstrike_wy 0
# высота полёта = max(старт, цель+30, рельеф+20)
execute store result score #y airstrike run data get entity @s Pos[1] 100
execute store result score #ty airstrike run data get storage airstrike:tmp d.ty 100
scoreboard players add #ty airstrike 3000
scoreboard players operation #y airstrike > #ty airstrike
scoreboard players set #gy airstrike -9999999
execute positioned over motion_blocking run summon minecraft:marker ~ ~20 ~ {Tags:["airstrike","airstrike_tmp"]}
execute store result score #gy airstrike run data get entity @e[type=minecraft:marker,tag=airstrike_tmp,limit=1] Pos[1] 100
kill @e[type=minecraft:marker,tag=airstrike_tmp]
scoreboard players operation #y airstrike > #gy airstrike
execute store result entity @s Pos[1] double 0.01 run scoreboard players get #y airstrike
scoreboard players operation @s airstrike_hc = #y airstrike
scoreboard players operation @s airstrike_h = #y airstrike
execute at @s run function airstrike:drone/face with entity @s data
execute at @s run function airstrike:drone/build
