function airstrike:fx/spray
function airstrike:fx/debris
execute if data storage airstrike:cfg {shatter:1b} run function airstrike:fx/shatter
particle minecraft:explosion_emitter ~ ~2 ~ 4 2 4 0 4 force @a
