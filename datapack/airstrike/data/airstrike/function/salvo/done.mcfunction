scoreboard players operation #own airstrike = @s airstrike_id
execute as @a if score @s airstrike_own = #own airstrike run title @s actionbar {"text":"Залп выпущен полностью","color":"gray"}
tag @s add airstrike_dead
execute if data entity @s data.sl.id run function airstrike:untag_if_free with entity @s data.sl
kill @s
