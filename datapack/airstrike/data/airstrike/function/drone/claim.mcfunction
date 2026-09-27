scoreboard players operation #cur airstrike_id = @s airstrike_id
execute as @e[tag=airstrike_orphan] if score @s airstrike_id = #cur airstrike_id run tag @s remove airstrike_orphan
