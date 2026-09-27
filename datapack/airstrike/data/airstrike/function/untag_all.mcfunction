# снять пометки целей: у сущностей бывают только теги airstrike_te<N>; перебираем последние 256
execute store result storage airstrike:tmp ua.n int 1 run scoreboard players get #tn airstrike
function airstrike:untag_loop with storage airstrike:tmp ua
