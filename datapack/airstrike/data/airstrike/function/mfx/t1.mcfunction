function airstrike:mfx/spray
function airstrike:mfx/debris
execute if data storage airstrike:cfg {shatter:1b} run function airstrike:mfx/shatter
particle minecraft:explosion_emitter ~ ~3 ~ 7 3 7 0 8 force @a
