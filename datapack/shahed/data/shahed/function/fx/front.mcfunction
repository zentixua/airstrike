# фронт звука и ударной волны: 17 блоков за тик (≈ скорость звука)
scoreboard players operation #band shahed = @s shahed_t
scoreboard players operation #a shahed = @s shahed_t
scoreboard players remove #a shahed 1
scoreboard players operation #a shahed *= #1700 shahed
scoreboard players operation #b shahed = #a shahed
scoreboard players add #b shahed 1699
execute store result storage shahed:tmp band.a double 0.01 run scoreboard players get #a shahed
execute store result storage shahed:tmp band.b double 0.01 run scoreboard players get #b shahed
function shahed:fx/band with storage shahed:tmp band
