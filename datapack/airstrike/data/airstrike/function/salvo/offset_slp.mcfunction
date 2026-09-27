execute store result score #a airstrike run data get storage airstrike:tmp sl.px 100
scoreboard players operation #b airstrike = #dx airstrike
scoreboard players operation #b airstrike *= #100 airstrike
scoreboard players operation #a airstrike += #b airstrike
execute store result storage airstrike:tmp sl.px double 0.01 run scoreboard players get #a airstrike
execute store result score #a airstrike run data get storage airstrike:tmp sl.pz 100
scoreboard players operation #b airstrike = #dz airstrike
scoreboard players operation #b airstrike *= #100 airstrike
scoreboard players operation #a airstrike += #b airstrike
execute store result storage airstrike:tmp sl.pz double 0.01 run scoreboard players get #a airstrike
