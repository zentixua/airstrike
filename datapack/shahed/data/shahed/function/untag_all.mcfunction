# снять пометки целей: у сущностей бывают только теги shahed_te<N>; перебираем последние 256
execute store result storage shahed:tmp ua.n int 1 run scoreboard players get #tn shahed
function shahed:untag_loop with storage shahed:tmp ua
