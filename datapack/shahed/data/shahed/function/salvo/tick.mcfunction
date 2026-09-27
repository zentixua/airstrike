scoreboard players remove @s shahed_t 1
execute if score @s shahed_t matches 1.. run return 0
execute if score @s shahed_E matches ..0 run return run function shahed:salvo/done
function shahed:salvo/fire
scoreboard players remove @s shahed_E 1
execute if score @s shahed_ph matches 1 store result score @s shahed_t run random value 20..40
execute if score @s shahed_ph matches 2 store result score @s shahed_t run random value 15..30
execute if score @s shahed_ph matches 3 store result score @s shahed_t run random value 60..80
function shahed:salvo/hud
