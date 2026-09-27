data remove storage airstrike:tmp pl.name
loot replace entity @e[type=minecraft:armor_stand,tag=airstrike_namer,limit=1] armor.head loot airstrike:player_head
data modify storage airstrike:tmp pl.name set from entity @e[type=minecraft:armor_stand,tag=airstrike_namer,limit=1] ArmorItems[3].components."minecraft:profile".name
execute if data storage airstrike:tmp pl.name run function airstrike:ui/player_btn_m with storage airstrike:tmp pl
