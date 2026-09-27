# снять пометку с цели, если за ней больше никто не летит (другой снаряд или незаконченный залп)
$execute if entity @e[type=minecraft:marker,tag=airstrike_root,tag=!airstrike_dead,nbt={data:{sl:{id:$(id)}}}] run return 0
$execute if entity @e[type=minecraft:marker,tag=airstrike_salvo,tag=!airstrike_dead,nbt={data:{sl:{id:$(id)}}}] run return 0
$tag @e[tag=airstrike_te$(id)] remove airstrike_te$(id)
