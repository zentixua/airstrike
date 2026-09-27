scoreboard players set #cls airstrike 8
execute if block ~ ~ ~ #airstrike:passable run scoreboard players set #cls airstrike 0
execute if block ~ ~ ~ #airstrike:bb_soft run scoreboard players set #cls airstrike 1
execute if block ~ ~ ~ #airstrike:bb_rock run scoreboard players set #cls airstrike 2
execute if block ~ ~ ~ #airstrike:bb_deep run scoreboard players set #cls airstrike 3
execute if block ~ ~ ~ #airstrike:bb_hard run scoreboard players set #cls airstrike 4
execute if block ~ ~ ~ #airstrike:bb_vhard run scoreboard players set #cls airstrike 5
execute if block ~ ~ ~ #airstrike:bb_stop run scoreboard players set #cls airstrike 6
execute if block ~ ~ ~ #airstrike:bb_fluid run scoreboard players set #cls airstrike 7
# датчик пустот: вошла в полость после ≥4 блоков породы — подрыв внутри неё
execute if score #cls airstrike matches 0 if score @s airstrike_trav matches 4.. run return run function airstrike:bunker/void
execute if score #cls airstrike matches 0 run return run tp @s ~ ~ ~
execute if score #cls airstrike matches 6 run return run function airstrike:bunker/stop
# сопротивление: грунт 12, порода 30, глубинный сланец 40, бетон/кирпич 110, обсидиан 300, вода 5, прочее 25
execute if score #cls airstrike matches 1 run scoreboard players set #cost airstrike 12
execute if score #cls airstrike matches 2 run scoreboard players set #cost airstrike 30
execute if score #cls airstrike matches 3 run scoreboard players set #cost airstrike 40
execute if score #cls airstrike matches 4 run scoreboard players set #cost airstrike 110
execute if score #cls airstrike matches 5 run scoreboard players set #cost airstrike 300
execute if score #cls airstrike matches 7 run scoreboard players set #cost airstrike 5
execute if score #cls airstrike matches 8 run scoreboard players set #cost airstrike 25
scoreboard players operation @s airstrike_E -= #cost airstrike
scoreboard players add @s airstrike_trav 1
function airstrike:bunker/carve
tp @s ~ ~ ~
execute if data entity @s data{goal:1b} run function airstrike:bunker/home with entity @s data
function airstrike:bunker/wobble
execute if score @s airstrike_E matches ..0 run function airstrike:bunker/stop
execute if score @s airstrike_trav matches 70.. run function airstrike:bunker/stop
