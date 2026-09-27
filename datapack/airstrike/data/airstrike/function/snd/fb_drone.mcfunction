scoreboard players set #k airstrike 130
scoreboard players operation #fp airstrike = #p airstrike
scoreboard players operation #fp airstrike *= #k airstrike
scoreboard players operation #fp airstrike /= #100 airstrike
execute if score #fp airstrike matches 201.. run scoreboard players set #fp airstrike 200
execute store result storage airstrike:tmp snd.p double 0.01 run scoreboard players get #fp airstrike
data modify storage airstrike:tmp snd.e set value "immersive_aircraft:propeller_tiny"
function airstrike:snd/play with storage airstrike:tmp snd
scoreboard players set #k airstrike 190
scoreboard players operation #fp airstrike = #p airstrike
scoreboard players operation #fp airstrike *= #k airstrike
scoreboard players operation #fp airstrike /= #100 airstrike
execute if score #fp airstrike matches 201.. run scoreboard players set #fp airstrike 200
execute store result storage airstrike:tmp snd.p double 0.01 run scoreboard players get #fp airstrike
data modify storage airstrike:tmp snd.e set value "petrochem:engine"
function airstrike:snd/play with storage airstrike:tmp snd
