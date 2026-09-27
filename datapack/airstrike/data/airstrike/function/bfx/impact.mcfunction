# ударная волна от входа: небольшой кратер, фонтан грунта, звуковой удар + тупой удар
summon minecraft:creeper ~ ~1 ~ {Fuse:0s,ignited:1b,ExplosionRadius:4b,Silent:1b,NoAI:1b,Invulnerable:1b,CustomName:'"Бетонобойная бомба"',Tags:["airstrike"]}
particle minecraft:explosion_emitter ~ ~1 ~ 0 0 0 0 1 force @a
particle minecraft:explosion ~ ~1 ~ 1 1 1 0 12 force @a
execute as @e[type=minecraft:marker,tag=airstrike_vent] if score @s airstrike_id = #cur_b airstrike at @s run function airstrike:fx/spray
stopsound @a[distance=..60] ambient
stopsound @a[distance=..400] ambient snassets:weapons/bomb_whistle
playsound airstrike:bb.crack master @a[distance=..300,scores={airstrike_mode=2..}] ~ ~ ~ 12 1 0.5
playsound airstrike:bb.impact master @a[distance=..300,scores={airstrike_mode=2..}] ~ ~ ~ 12 1 0.6
playsound minecraft:entity.firework_rocket.large_blast master @a[distance=..300,scores={airstrike_mode=..1}] ~ ~ ~ 10 0.5 0.5
playsound minecraft:block.anvil.land master @a[distance=..300,scores={airstrike_mode=..1}] ~ ~ ~ 8 0.5 0.5
playsound minecraft:entity.generic.explode master @a[distance=..300,scores={airstrike_mode=..1}] ~ ~ ~ 8 0.6 0.5
execute as @a[distance=..60] unless score @s airstrike_quake matches 20.. run scoreboard players set @s airstrike_quake 20

# кинетический удар: рядом с точкой попадания — смертельно
execute as @e[distance=..4.5,tag=!airstrike,type=!minecraft:marker] run damage @s 60 minecraft:explosion
execute as @e[distance=4.5..9,tag=!airstrike,type=!minecraft:marker] run damage @s 22 minecraft:explosion
execute as @e[distance=9..14,tag=!airstrike,type=!minecraft:marker] run damage @s 7 minecraft:explosion
