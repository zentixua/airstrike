stopsound @s ambient
execute if predicate airstrike:sky run return run function airstrike:bfx/arrive_surface
execute if score #band airstrike matches ..4 run return run function airstrike:bfx/arrive_cave
function airstrike:bfx/arrive_surface
