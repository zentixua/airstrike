scoreboard players set #k shahed 55
scoreboard players operation #fp shahed = #p shahed
scoreboard players operation #fp shahed *= #k shahed
scoreboard players operation #fp shahed /= #100 shahed
execute if score #fp shahed matches 201.. run scoreboard players set #fp shahed 200
execute store result storage shahed:tmp snd.p double 0.01 run scoreboard players get #fp shahed
data modify storage shahed:tmp snd.e set value "minecraft:item.elytra.flying"
stopsound @s ambient minecraft:item.elytra.flying
function shahed:snd/play with storage shahed:tmp snd
