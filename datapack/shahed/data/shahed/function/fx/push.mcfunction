# ударная волна: заряд ветра между игроком и эпицентром отбрасывает от взрыва
execute facing entity @e[type=minecraft:marker,tag=shahed_fx,sort=nearest,limit=1] feet rotated ~ 0 positioned ^ ^ ^1.2 run summon minecraft:wind_charge ~ ~0.3 ~ {Motion:[0.0d,-1.5d,0.0d]}
execute if score #band shahed matches 1 facing entity @e[type=minecraft:marker,tag=shahed_fx,sort=nearest,limit=1] feet rotated ~ 0 positioned ^ ^ ^1.2 run summon minecraft:wind_charge ~ ~0.6 ~ {Motion:[0.0d,-1.5d,0.0d]}
execute if score #band shahed matches 1 run effect give @s minecraft:nausea 7 0 true
execute if score #band shahed matches 2 run effect give @s minecraft:nausea 4 0 true
