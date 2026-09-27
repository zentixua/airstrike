$execute store result score #dx shahed run random value -$(r)..$(r)
$execute store result score #dz shahed run random value -$(r)..$(r)
$scoreboard players set #r2 shahed $(r)
scoreboard players operation #r2 shahed *= #r2 shahed
scoreboard players operation #q shahed = #dx shahed
scoreboard players operation #q shahed *= #dx shahed
scoreboard players operation #q2 shahed = #dz shahed
scoreboard players operation #q2 shahed *= #dz shahed
scoreboard players operation #q shahed += #q2 shahed
scoreboard players add #try shahed 1
execute if score #q shahed > #r2 shahed if score #try shahed matches ..8 run return run function shahed:salvo/pick with storage shahed:tmp sv2
execute store result storage shahed:tmp sp2.x int 1 run scoreboard players get #dx shahed
execute store result storage shahed:tmp sp2.z int 1 run scoreboard players get #dz shahed
