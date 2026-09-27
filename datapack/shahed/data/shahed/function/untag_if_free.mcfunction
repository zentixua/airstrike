# снять пометку с цели, если за ней больше никто не летит (другой снаряд или незаконченный залп)
$execute if entity @e[type=minecraft:marker,tag=shahed_root,tag=!shahed_dead,nbt={data:{sl:{id:$(id)}}}] run return 0
$execute if entity @e[type=minecraft:marker,tag=shahed_salvo,tag=!shahed_dead,nbt={data:{sl:{id:$(id)}}}] run return 0
$tag @e[tag=shahed_te$(id)] remove shahed_te$(id)
