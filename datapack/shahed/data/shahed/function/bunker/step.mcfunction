scoreboard players set #cls shahed 8
execute if block ~ ~ ~ #shahed:passable run scoreboard players set #cls shahed 0
execute if block ~ ~ ~ #shahed:bb_soft run scoreboard players set #cls shahed 1
execute if block ~ ~ ~ #shahed:bb_rock run scoreboard players set #cls shahed 2
execute if block ~ ~ ~ #shahed:bb_deep run scoreboard players set #cls shahed 3
execute if block ~ ~ ~ #shahed:bb_hard run scoreboard players set #cls shahed 4
execute if block ~ ~ ~ #shahed:bb_vhard run scoreboard players set #cls shahed 5
execute if block ~ ~ ~ #shahed:bb_stop run scoreboard players set #cls shahed 6
execute if block ~ ~ ~ #shahed:bb_fluid run scoreboard players set #cls shahed 7
# датчик пустот: вошла в полость после ≥4 блоков породы — подрыв внутри неё
execute if score #cls shahed matches 0 if score @s shahed_trav matches 4.. run return run function shahed:bunker/void
execute if score #cls shahed matches 0 run return run tp @s ~ ~ ~
execute if score #cls shahed matches 6 run return run function shahed:bunker/stop
# сопротивление: грунт 12, порода 30, глубинный сланец 40, бетон/кирпич 110, обсидиан 300, вода 5, прочее 25
execute if score #cls shahed matches 1 run scoreboard players set #cost shahed 12
execute if score #cls shahed matches 2 run scoreboard players set #cost shahed 30
execute if score #cls shahed matches 3 run scoreboard players set #cost shahed 40
execute if score #cls shahed matches 4 run scoreboard players set #cost shahed 110
execute if score #cls shahed matches 5 run scoreboard players set #cost shahed 300
execute if score #cls shahed matches 7 run scoreboard players set #cost shahed 5
execute if score #cls shahed matches 8 run scoreboard players set #cost shahed 25
scoreboard players operation @s shahed_E -= #cost shahed
scoreboard players add @s shahed_trav 1
function shahed:bunker/carve
tp @s ~ ~ ~
execute if data entity @s data{goal:1b} run function shahed:bunker/home with entity @s data
function shahed:bunker/wobble
execute if score @s shahed_E matches ..0 run function shahed:bunker/stop
execute if score @s shahed_trav matches 70.. run function shahed:bunker/stop
