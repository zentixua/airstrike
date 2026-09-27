# фонтан грунта из воронки и пылевое облако цвета местности
$particle minecraft:block{block_state:{Name:"$(b)"}} ~ ~1 ~ 1.5 1.5 1.5 0.9 350 force @a
$particle minecraft:block{block_state:{Name:"$(b)"}} ~ ~3 ~ 4 3 4 0.4 150 force @a
$particle minecraft:dust{color:[$(r)f,$(g)f,$(bl)f],scale:4.0f} ~ ~1.5 ~ 5 1.2 5 0.02 150 force @a
