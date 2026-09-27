data remove storage shahed:tmp pl.name
loot replace entity @e[type=minecraft:armor_stand,tag=shahed_namer,limit=1] armor.head loot shahed:player_head
data modify storage shahed:tmp pl.name set from entity @e[type=minecraft:armor_stand,tag=shahed_namer,limit=1] ArmorItems[3].components."minecraft:profile".name
execute if data storage shahed:tmp pl.name run function shahed:ui/player_btn_m with storage shahed:tmp pl
