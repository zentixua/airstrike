scoreboard players remove @s airstrike_t 1
execute if score @s airstrike_t matches 1.. run return 0
execute if score @s airstrike_E matches ..0 run return run function airstrike:salvo/done
function airstrike:salvo/fire
scoreboard players remove @s airstrike_E 1
execute if score @s airstrike_ph matches 1 store result score @s airstrike_t run random value 20..40
execute if score @s airstrike_ph matches 2 store result score @s airstrike_t run random value 15..30
execute if score @s airstrike_ph matches 3 store result score @s airstrike_t run random value 60..80
function airstrike:salvo/hud
