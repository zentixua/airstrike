stopsound @s ambient
execute if predicate shahed:sky run return run function shahed:bfx/arrive_surface
execute if score #band shahed matches ..4 run return run function shahed:bfx/arrive_cave
function shahed:bfx/arrive_surface
