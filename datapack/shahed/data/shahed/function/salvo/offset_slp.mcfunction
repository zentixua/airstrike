execute store result score #a shahed run data get storage shahed:tmp sl.px 100
scoreboard players operation #b shahed = #dx shahed
scoreboard players operation #b shahed *= #100 shahed
scoreboard players operation #a shahed += #b shahed
execute store result storage shahed:tmp sl.px double 0.01 run scoreboard players get #a shahed
execute store result score #a shahed run data get storage shahed:tmp sl.pz 100
scoreboard players operation #b shahed = #dz shahed
scoreboard players operation #b shahed *= #100 shahed
scoreboard players operation #a shahed += #b shahed
execute store result storage shahed:tmp sl.pz double 0.01 run scoreboard players get #a shahed
