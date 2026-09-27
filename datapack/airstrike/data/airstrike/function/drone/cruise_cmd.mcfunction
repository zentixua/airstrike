function airstrike:fly/terrain
scoreboard players operation #H airstrike = #tmax airstrike
scoreboard players add #H airstrike 1800
scoreboard players operation #H airstrike > @s airstrike_hc
scoreboard players operation #t2 airstrike = @s airstrike_ty
scoreboard players operation #t2 airstrike *= #10 airstrike
scoreboard players add #t2 airstrike 3000
scoreboard players operation #H airstrike > #t2 airstrike
function airstrike:fly/alt_cmd
scoreboard players operation #wcmd airstrike = #thd airstrike
scoreboard players operation #wcmd airstrike -= #cp airstrike
scoreboard players operation #wcmd airstrike *= #12 airstrike
scoreboard players operation #wcmd airstrike /= #100 airstrike
scoreboard players set #W airstrike 120
scoreboard players set #A airstrike 15
