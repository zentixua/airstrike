scoreboard players set @s shahed_ph 0
scoreboard players set @s shahed_t 0
function shahed:missile/face with entity @s data
execute at @s run function shahed:missile/build
