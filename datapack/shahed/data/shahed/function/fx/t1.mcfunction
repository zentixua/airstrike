function shahed:fx/spray
function shahed:fx/debris
execute if data storage shahed:cfg {shatter:1b} run function shahed:fx/shatter
particle minecraft:explosion_emitter ~ ~2 ~ 4 2 4 0 4 force @a
