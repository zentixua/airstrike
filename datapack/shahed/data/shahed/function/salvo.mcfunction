# /function shahed:salvo {type:"drone",count:6,radius:25} — залп вокруг того места, где ты стоишь
$data modify storage shahed:tmp sv set value {type:"$(type)",count:$(count),r:$(radius)}
data remove storage shahed:tmp sl
execute at @s run function shahed:salvo/begin
