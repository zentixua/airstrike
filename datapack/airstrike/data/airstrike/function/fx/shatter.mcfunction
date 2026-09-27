# ударная волна выбивает стёкла в радиусе 16 блоков
execute store result score #g airstrike run fill ~-16 ~-6 ~-16 ~16 ~12 ~16 minecraft:air replace #minecraft:impermeable
execute store result score #g2 airstrike run fill ~-16 ~-6 ~-16 ~16 ~12 ~16 minecraft:air replace minecraft:glass_pane
scoreboard players operation #g airstrike += #g2 airstrike
execute if score #g airstrike matches 1.. run playsound minecraft:block.glass.break block @a ~ ~ ~ 4 0.8
execute if score #g airstrike matches 1.. run playsound minecraft:block.glass.break block @a ~ ~ ~ 4 1.2
execute if score #g airstrike matches 1.. run function airstrike:fx/shatter_fx
