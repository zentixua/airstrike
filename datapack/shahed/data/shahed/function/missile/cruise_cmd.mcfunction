function shahed:fly/terrain_far
scoreboard players operation #H shahed = #tmax shahed
scoreboard players add #H shahed 1200
scoreboard players operation #t2 shahed = @s shahed_ty
scoreboard players operation #t2 shahed *= #10 shahed
scoreboard players add #t2 shahed 1200
scoreboard players operation #H shahed > #t2 shahed
function shahed:fly/alt_cmd
scoreboard players operation #wcmd shahed = #thd shahed
scoreboard players operation #wcmd shahed -= #cp shahed
scoreboard players operation #wcmd shahed *= #30 shahed
scoreboard players operation #wcmd shahed /= #100 shahed
scoreboard players set #W shahed 800
scoreboard players set #A shahed 180
