function shahed:fly/terrain
scoreboard players operation #H shahed = #tmax shahed
scoreboard players add #H shahed 1800
scoreboard players operation #H shahed > @s shahed_hc
scoreboard players operation #t2 shahed = @s shahed_ty
scoreboard players operation #t2 shahed *= #10 shahed
scoreboard players add #t2 shahed 3000
scoreboard players operation #H shahed > #t2 shahed
function shahed:fly/alt_cmd
scoreboard players operation #wcmd shahed = #thd shahed
scoreboard players operation #wcmd shahed -= #cp shahed
scoreboard players operation #wcmd shahed *= #12 shahed
scoreboard players operation #wcmd shahed /= #100 shahed
scoreboard players set #W shahed 120
scoreboard players set #A shahed 15
