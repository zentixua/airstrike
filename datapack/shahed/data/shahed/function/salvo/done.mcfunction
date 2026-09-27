scoreboard players operation #own shahed = @s shahed_id
execute as @a if score @s shahed_own = #own shahed run title @s actionbar {"text":"Залп выпущен полностью","color":"gray"}
tag @s add shahed_dead
execute if data entity @s data.sl.id run function shahed:untag_if_free with entity @s data.sl
kill @s
