scoreboard players operation #cur shahed_id = @s shahed_id
execute as @e[tag=shahed_part] if score @s shahed_id = #cur shahed_id run kill @s
stopsound @a[scores={shahed_mode=..1}] ambient minecraft:item.elytra.flying
kill @s
return 1
