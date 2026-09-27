$execute store result score #dx airstrike run random value -$(r)..$(r)
$execute store result score #dz airstrike run random value -$(r)..$(r)
$scoreboard players set #r2 airstrike $(r)
scoreboard players operation #r2 airstrike *= #r2 airstrike
scoreboard players operation #q airstrike = #dx airstrike
scoreboard players operation #q airstrike *= #dx airstrike
scoreboard players operation #q2 airstrike = #dz airstrike
scoreboard players operation #q2 airstrike *= #dz airstrike
scoreboard players operation #q airstrike += #q2 airstrike
scoreboard players add #try airstrike 1
execute if score #q airstrike > #r2 airstrike if score #try airstrike matches ..8 run return run function airstrike:salvo/pick with storage airstrike:tmp sv2
execute store result storage airstrike:tmp sp2.x int 1 run scoreboard players get #dx airstrike
execute store result storage airstrike:tmp sp2.z int 1 run scoreboard players get #dz airstrike
