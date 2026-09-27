scoreboard players operation #cur shahed_id = @s shahed_id
execute as @e[tag=shahed_orphan] if score @s shahed_id = #cur shahed_id run tag @s remove shahed_orphan
