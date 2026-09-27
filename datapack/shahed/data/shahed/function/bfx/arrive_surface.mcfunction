# на поверхности — глухой удар из-под земли
execute if entity @s[scores={shahed_mode=2..}] run playsound shahed:bb.deep master @s ~ ~ ~ 1 1 1
execute unless entity @s[scores={shahed_mode=2..}] run playsound snassets:explosions/explosion_distant_heavy master @s ~ ~ ~ 1 0.6 1
execute if score #band shahed matches ..6 run playsound minecraft:entity.lightning_bolt.thunder master @s ~ ~ ~ 0.6 0.4 0.6
execute if data storage shahed:cfg {shake:1b} if score #band shahed matches ..8 run scoreboard players set @s shahed_shake 16
