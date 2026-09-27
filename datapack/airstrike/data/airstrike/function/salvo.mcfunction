# /function airstrike:salvo {type:"drone",count:6,radius:25} — залп вокруг того места, где ты стоишь
$data modify storage airstrike:tmp sv set value {type:"$(type)",count:$(count),r:$(radius)}
data remove storage airstrike:tmp sl
execute at @s run function airstrike:salvo/begin
