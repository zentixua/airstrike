# под землёй рядом — взрыв в замкнутом пространстве: жёстко и с долгим эхом
execute if entity @s[scores={airstrike_mode=2..}] run playsound airstrike:bb.cave master @s ~ ~ ~ 1 1 1
execute if entity @s[scores={airstrike_mode=2..}] run playsound airstrike:blast.sub master @s ~ ~ ~ 1 0.8 1
execute unless entity @s[scores={airstrike_mode=2..}] run playsound snassets:explosions/explosion_heavy1 master @s ~ ~ ~ 1 0.8 1
playsound minecraft:entity.generic.explode master @s ~ ~ ~ 1 0.5 1
playsound snassets:debris/debris_vehicle0 master @s ~ ~ ~ 1 0.7 1
execute if data storage airstrike:cfg {shake:1b} if score #band airstrike matches 1 run scoreboard players set @s airstrike_shake 34
execute if data storage airstrike:cfg {shake:1b} if score #band airstrike matches 2 run scoreboard players set @s airstrike_shake 30
execute if data storage airstrike:cfg {shake:1b} if score #band airstrike matches 3..4 run scoreboard players set @s airstrike_shake 26
execute if score #band airstrike matches 1..2 run function airstrike:bfx/push
execute if score #band airstrike matches 1 run effect give @s minecraft:darkness 4 0 true
execute if score #band airstrike matches 1..2 run effect give @s minecraft:nausea 8 0 true
