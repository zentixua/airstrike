scoreboard players set @s airstrike_ph 0
scoreboard players set @s airstrike_t 0
function airstrike:missile/face with entity @s data
execute at @s run function airstrike:missile/build
