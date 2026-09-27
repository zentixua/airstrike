# сейсмическая волна в породе быстрее звука: трясёт сразу, звук придёт позже
execute positioned as @e[type=minecraft:marker,tag=shahed_bfx,sort=nearest,limit=1] if entity @s[distance=..40] unless score @s shahed_quake matches 80.. run scoreboard players set @s shahed_quake 80
execute positioned as @e[type=minecraft:marker,tag=shahed_bfx,sort=nearest,limit=1] if entity @s[distance=40..100] unless score @s shahed_quake matches 60.. run scoreboard players set @s shahed_quake 60
execute positioned as @e[type=minecraft:marker,tag=shahed_bfx,sort=nearest,limit=1] if entity @s[distance=100..] unless score @s shahed_quake matches 40.. run scoreboard players set @s shahed_quake 40
execute if entity @s[scores={shahed_mode=2..}] run playsound shahed:bb.quake master @s ~ ~ ~ 1 1 1
execute unless entity @s[scores={shahed_mode=2..}] run playsound minecraft:entity.warden.emerge master @s ~ ~ ~ 1 0.5 1
