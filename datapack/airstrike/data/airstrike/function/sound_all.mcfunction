# хост: у всех онлайн стоит Airstrike Sounds — включить полные звуки всем сразу
execute as @a run scoreboard players set @s airstrike_mode 2
tellraw @a {"text":"♪ Полные звуки Airstrike Sounds включены для всех игроков.","color":"green"}
