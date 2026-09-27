# смещение этого снаряда относительно движущейся цели
data remove storage airstrike:tmp wo
execute store result storage airstrike:tmp wo.x int 100 run scoreboard players get #dx airstrike
execute store result storage airstrike:tmp wo.z int 100 run scoreboard players get #dz airstrike
execute if data storage airstrike:tmp sl.id run function airstrike:salvo/offset_te
execute if data storage airstrike:tmp sl.px run function airstrike:salvo/offset_slp
