# ждём, пока отвоет сирена, потом ракета выходит на цель
execute if score @s airstrike_t matches 100.. at @s run function airstrike:missile/go
return 0
