function shahed:mfx/spray
function shahed:mfx/debris
execute if data storage shahed:cfg {shatter:1b} run function shahed:mfx/shatter
particle minecraft:explosion_emitter ~ ~3 ~ 7 3 7 0 8 force @a
