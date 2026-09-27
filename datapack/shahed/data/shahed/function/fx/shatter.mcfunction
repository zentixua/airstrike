# ударная волна выбивает стёкла в радиусе 16 блоков
execute store result score #g shahed run fill ~-16 ~-6 ~-16 ~16 ~12 ~16 minecraft:air replace #minecraft:impermeable
execute store result score #g2 shahed run fill ~-16 ~-6 ~-16 ~16 ~12 ~16 minecraft:air replace minecraft:glass_pane
scoreboard players operation #g shahed += #g2 shahed
execute if score #g shahed matches 1.. run playsound minecraft:block.glass.break block @a ~ ~ ~ 4 0.8
execute if score #g shahed matches 1.. run playsound minecraft:block.glass.break block @a ~ ~ ~ 4 1.2
execute if score #g shahed matches 1.. run function shahed:fx/shatter_fx
