execute store result score #a airstrike run data get storage airstrike:tmp sl.ox
scoreboard players operation #b airstrike = #dx airstrike
scoreboard players operation #b airstrike *= #100 airstrike
scoreboard players operation #a airstrike += #b airstrike
execute store result storage airstrike:tmp sl.ox int 1 run scoreboard players get #a airstrike
execute store result score #a airstrike run data get storage airstrike:tmp sl.oz
scoreboard players operation #b airstrike = #dz airstrike
scoreboard players operation #b airstrike *= #100 airstrike
scoreboard players operation #a airstrike += #b airstrike
execute store result storage airstrike:tmp sl.oz int 1 run scoreboard players get #a airstrike
