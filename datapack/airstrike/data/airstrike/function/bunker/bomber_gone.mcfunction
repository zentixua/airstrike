scoreboard players operation #cur airstrike_id = @s airstrike_id
execute as @e[tag=airstrike_part] if score @s airstrike_id = #cur airstrike_id run kill @s
stopsound @a[scores={airstrike_mode=..1}] ambient minecraft:item.elytra.flying
kill @s
return 1
