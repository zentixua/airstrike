# на поверхности — глухой удар из-под земли
execute if entity @s[scores={airstrike_mode=2..}] run playsound airstrike:bb.deep master @s ~ ~ ~ 1 1 1
execute unless entity @s[scores={airstrike_mode=2..}] run playsound snassets:explosions/explosion_distant_heavy master @s ~ ~ ~ 1 0.6 1
execute if score #band airstrike matches ..6 run playsound minecraft:entity.lightning_bolt.thunder master @s ~ ~ ~ 0.6 0.4 0.6
execute if data storage airstrike:cfg {shake:1b} if score #band airstrike matches ..8 run scoreboard players set @s airstrike_shake 16
