scoreboard players set #appr shahed 0
execute if score #vr shahed matches ..-1 run scoreboard players set #appr shahed 1
execute if score #nodop shahed matches 1 run scoreboard players set #appr shahed 1
# спереди — свист вентилятора, сзади — рёв струи, в пике — пронзительный свист
execute if score #appr shahed matches 0 if score #var shahed matches 0 run data modify storage shahed:tmp snd.e set value "shahed:missile.rear.near"
execute if score #appr shahed matches 1 unless score #sph shahed matches 2 if score #var shahed matches 0 run data modify storage shahed:tmp snd.e set value "shahed:missile.front.near"
execute if score #appr shahed matches 1 if score #sph shahed matches 2 if score #var shahed matches 0 run data modify storage shahed:tmp snd.e set value "shahed:missile.dive.near"
execute if score #appr shahed matches 0 if score #var shahed matches 1 run data modify storage shahed:tmp snd.e set value "shahed:missile.rear.mid"
execute if score #appr shahed matches 1 unless score #sph shahed matches 2 if score #var shahed matches 1 run data modify storage shahed:tmp snd.e set value "shahed:missile.front.mid"
execute if score #appr shahed matches 1 if score #sph shahed matches 2 if score #var shahed matches 1 run data modify storage shahed:tmp snd.e set value "shahed:missile.dive.mid"
execute if score #appr shahed matches 0 if score #var shahed matches 2 run data modify storage shahed:tmp snd.e set value "shahed:missile.rear.far"
execute if score #appr shahed matches 1 unless score #sph shahed matches 2 if score #var shahed matches 2 run data modify storage shahed:tmp snd.e set value "shahed:missile.front.far"
execute if score #appr shahed matches 1 if score #sph shahed matches 2 if score #var shahed matches 2 run data modify storage shahed:tmp snd.e set value "shahed:missile.dive.far"
