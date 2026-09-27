tag @s remove airstrike_newfx
scoreboard players set @s airstrike_t 0
function airstrike:debris/sample
function airstrike:fx/spray_pick
execute store result score #by airstrike run data get entity @s Pos[1] 100
execute positioned over motion_blocking run summon minecraft:marker ~ ~ ~ {Tags:["airstrike","airstrike_surf","airstrike_newsurf"]}
execute as @e[type=minecraft:marker,tag=airstrike_newsurf] at @s run function airstrike:bfx/surf_init
function airstrike:bfx/cavity
function airstrike:cfg_clamp
function airstrike:bfx/main_blast with storage airstrike:cfg
# ударная волна в породе: достаёт и за камнем, без прямой видимости
execute as @e[distance=..10,tag=!airstrike,type=!minecraft:marker] run damage @s 200 minecraft:explosion
execute as @e[distance=10..16,tag=!airstrike,type=!minecraft:marker] run damage @s 40 minecraft:explosion
execute as @e[distance=16..24,tag=!airstrike,type=!minecraft:marker] run damage @s 12 minecraft:explosion
execute as @a[distance=24..34] run damage @s 4 minecraft:explosion
execute as @a[distance=..70] unless predicate airstrike:sky unless predicate airstrike:has_nv run tag @s add airstrike_nv
effect give @a[tag=airstrike_nv] minecraft:night_vision 1 0 true
execute as @a[distance=..320] at @s run function airstrike:bfx/seismic
