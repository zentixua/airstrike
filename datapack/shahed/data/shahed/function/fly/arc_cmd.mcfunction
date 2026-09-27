# наведение по дуге: ω = 2·G·v·(угол на цель − тангаж)/дальность, G = 1.6
scoreboard players operation #e shahed = #los shahed
scoreboard players operation #e shahed -= #cp shahed
scoreboard players operation #wcmd shahed = @s shahed_v
scoreboard players operation #wcmd shahed *= #e shahed
scoreboard players operation #wcmd shahed *= #32 shahed
scoreboard players operation #wcmd shahed /= #d shahed
scoreboard players operation #wcmd shahed /= #100 shahed
