tag @s remove shahed_newfx
scoreboard players set @s shahed_t 0
function shahed:debris/sample
function shahed:fx/spray_pick
execute store result score #by shahed run data get entity @s Pos[1] 100
execute positioned over motion_blocking run summon minecraft:marker ~ ~ ~ {Tags:["shahed","shahed_surf","shahed_newsurf"]}
execute as @e[type=minecraft:marker,tag=shahed_newsurf] at @s run function shahed:bfx/surf_init
function shahed:bfx/cavity
function shahed:cfg_clamp
function shahed:bfx/main_blast with storage shahed:cfg
# ударная волна в породе: достаёт и за камнем, без прямой видимости
execute as @e[distance=..10,tag=!shahed,type=!minecraft:marker] run damage @s 200 minecraft:explosion
execute as @e[distance=10..16,tag=!shahed,type=!minecraft:marker] run damage @s 40 minecraft:explosion
execute as @e[distance=16..24,tag=!shahed,type=!minecraft:marker] run damage @s 12 minecraft:explosion
execute as @a[distance=24..34] run damage @s 4 minecraft:explosion
execute as @a[distance=..70] unless predicate shahed:sky unless predicate shahed:has_nv run tag @s add shahed_nv
effect give @a[tag=shahed_nv] minecraft:night_vision 1 0 true
execute as @a[distance=..320] at @s run function shahed:bfx/seismic
