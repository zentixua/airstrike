scoreboard players set #st shahed 0
execute if data storage shahed:tmp sv{type:"drone"} run scoreboard players set #st shahed 1
execute if data storage shahed:tmp sv{type:"shahed"} run scoreboard players set #st shahed 1
execute if data storage shahed:tmp sv{type:"шахед"} run scoreboard players set #st shahed 1
execute if data storage shahed:tmp sv{type:"missile"} run scoreboard players set #st shahed 2
execute if data storage shahed:tmp sv{type:"ракета"} run scoreboard players set #st shahed 2
execute if data storage shahed:tmp sv{type:"bunker"} run scoreboard players set #st shahed 3
execute if data storage shahed:tmp sv{type:"бомба"} run scoreboard players set #st shahed 3
execute if score #st shahed matches 0 run return run tellraw @s {"text":"Тип залпа: drone, missile или bunker (шахед / ракета / бомба)","color":"red"}
function shahed:salvo/core
