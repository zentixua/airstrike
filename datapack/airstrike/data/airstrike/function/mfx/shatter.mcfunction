# ударная волна: стёкла в радиусе 26, листва в радиусе 11
execute store result score #g airstrike run fill ~-26 ~-8 ~-26 ~26 ~2 ~26 minecraft:air replace #minecraft:impermeable
execute store result score #g2 airstrike run fill ~-26 ~3 ~-26 ~26 ~13 ~26 minecraft:air replace #minecraft:impermeable
scoreboard players operation #g airstrike += #g2 airstrike
execute store result score #g2 airstrike run fill ~-26 ~14 ~-26 ~26 ~22 ~26 minecraft:air replace #minecraft:impermeable
scoreboard players operation #g airstrike += #g2 airstrike
execute store result score #g2 airstrike run fill ~-26 ~-8 ~-26 ~26 ~2 ~26 minecraft:air replace minecraft:glass_pane
scoreboard players operation #g airstrike += #g2 airstrike
execute store result score #g2 airstrike run fill ~-26 ~3 ~-26 ~26 ~13 ~26 minecraft:air replace minecraft:glass_pane
scoreboard players operation #g airstrike += #g2 airstrike
execute store result score #g2 airstrike run fill ~-26 ~14 ~-26 ~26 ~22 ~26 minecraft:air replace minecraft:glass_pane
scoreboard players operation #g airstrike += #g2 airstrike
execute if score #g airstrike matches 1.. run playsound minecraft:block.glass.break block @a ~ ~ ~ 6 0.7
execute if score #g airstrike matches 1.. run playsound minecraft:block.glass.break block @a ~ ~ ~ 6 1.0
execute if score #g airstrike matches 1.. run playsound minecraft:block.glass.break block @a ~ ~ ~ 6 1.3
execute if score #g airstrike matches 1.. run function airstrike:mfx/shatter_fx
execute store result score #l airstrike run fill ~-11 ~-3 ~-11 ~11 ~16 ~11 minecraft:air replace #minecraft:leaves
execute if score #l airstrike matches 1.. run playsound minecraft:block.grass.break block @a ~ ~ ~ 4 0.6
