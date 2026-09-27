fill ~ ~-1 ~ ~ ~1 ~ minecraft:air replace #airstrike:drillable
execute if predicate airstrike:p65 run fill ~1 ~ ~ ~1 ~ ~ minecraft:air replace #airstrike:drillable
execute if predicate airstrike:p65 run fill ~-1 ~ ~ ~-1 ~ ~ minecraft:air replace #airstrike:drillable
execute if predicate airstrike:p65 run fill ~ ~ ~1 ~ ~ ~1 minecraft:air replace #airstrike:drillable
execute if predicate airstrike:p65 run fill ~ ~ ~-1 ~ ~ ~-1 minecraft:air replace #airstrike:drillable
execute if predicate airstrike:p15 run fill ~-1 ~-1 ~-1 ~1 ~1 ~1 minecraft:air replace #airstrike:drillable
execute if predicate airstrike:p20 run fill ~2 ~-1 ~-1 ~2 ~1 ~1 minecraft:cobblestone replace #airstrike:bb_rock
execute if predicate airstrike:p20 run fill ~-2 ~-1 ~-1 ~-2 ~1 ~1 minecraft:cobblestone replace #airstrike:bb_rock
execute if predicate airstrike:p20 run fill ~-1 ~-1 ~2 ~1 ~1 ~2 minecraft:cobbled_deepslate replace #airstrike:bb_deep
execute if predicate airstrike:p20 run fill ~-1 ~-1 ~-2 ~1 ~1 ~-2 minecraft:gravel replace #airstrike:bb_soil
execute if predicate airstrike:p10 run fill ~ ~2 ~ ~ ~2 ~ minecraft:gravel replace minecraft:air
particle minecraft:poof ~ ~ ~ 0.4 0.4 0.4 0.05 3 force @a
execute as @a[distance=..1.8] run damage @s 100 minecraft:explosion
