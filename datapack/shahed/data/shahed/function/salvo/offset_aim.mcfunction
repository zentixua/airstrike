# смещение этого снаряда относительно движущейся цели
data remove storage shahed:tmp wo
execute store result storage shahed:tmp wo.x int 100 run scoreboard players get #dx shahed
execute store result storage shahed:tmp wo.z int 100 run scoreboard players get #dz shahed
execute if data storage shahed:tmp sl.id run function shahed:salvo/offset_te
execute if data storage shahed:tmp sl.px run function shahed:salvo/offset_slp
