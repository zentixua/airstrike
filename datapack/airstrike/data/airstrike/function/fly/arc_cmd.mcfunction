# наведение по дуге: ω = 2·G·v·(угол на цель − тангаж)/дальность, G = 1.6
scoreboard players operation #e airstrike = #los airstrike
scoreboard players operation #e airstrike -= #cp airstrike
scoreboard players operation #wcmd airstrike = @s airstrike_v
scoreboard players operation #wcmd airstrike *= #e airstrike
scoreboard players operation #wcmd airstrike *= #32 airstrike
scoreboard players operation #wcmd airstrike /= #d airstrike
scoreboard players operation #wcmd airstrike /= #100 airstrike
