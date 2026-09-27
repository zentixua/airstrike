execute store result score #a shahed run data get storage shahed:tmp sl.ox
scoreboard players operation #b shahed = #dx shahed
scoreboard players operation #b shahed *= #100 shahed
scoreboard players operation #a shahed += #b shahed
execute store result storage shahed:tmp sl.ox int 1 run scoreboard players get #a shahed
execute store result score #a shahed run data get storage shahed:tmp sl.oz
scoreboard players operation #b shahed = #dz shahed
scoreboard players operation #b shahed *= #100 shahed
scoreboard players operation #a shahed += #b shahed
execute store result storage shahed:tmp sl.oz int 1 run scoreboard players get #a shahed
