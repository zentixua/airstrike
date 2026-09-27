scoreboard players set #st airstrike 0
execute if data storage airstrike:tmp sv{type:"drone"} run scoreboard players set #st airstrike 1
execute if data storage airstrike:tmp sv{type:"shahed"} run scoreboard players set #st airstrike 1
execute if data storage airstrike:tmp sv{type:"шахед"} run scoreboard players set #st airstrike 1
execute if data storage airstrike:tmp sv{type:"missile"} run scoreboard players set #st airstrike 2
execute if data storage airstrike:tmp sv{type:"ракета"} run scoreboard players set #st airstrike 2
execute if data storage airstrike:tmp sv{type:"bunker"} run scoreboard players set #st airstrike 3
execute if data storage airstrike:tmp sv{type:"бомба"} run scoreboard players set #st airstrike 3
execute if score #st airstrike matches 0 run return run tellraw @s {"text":"Тип залпа: drone, missile или bunker (шахед / ракета / бомба)","color":"red"}
function airstrike:salvo/core
