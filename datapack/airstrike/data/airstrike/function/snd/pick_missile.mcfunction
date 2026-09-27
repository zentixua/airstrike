scoreboard players set #appr airstrike 0
execute if score #vr airstrike matches ..-1 run scoreboard players set #appr airstrike 1
execute if score #nodop airstrike matches 1 run scoreboard players set #appr airstrike 1
# спереди — свист вентилятора, сзади — рёв струи, в пике — пронзительный свист
execute if score #appr airstrike matches 0 if score #var airstrike matches 0 run data modify storage airstrike:tmp snd.e set value "airstrike:missile.rear.near"
execute if score #appr airstrike matches 1 unless score #sph airstrike matches 2 if score #var airstrike matches 0 run data modify storage airstrike:tmp snd.e set value "airstrike:missile.front.near"
execute if score #appr airstrike matches 1 if score #sph airstrike matches 2 if score #var airstrike matches 0 run data modify storage airstrike:tmp snd.e set value "airstrike:missile.dive.near"
execute if score #appr airstrike matches 0 if score #var airstrike matches 1 run data modify storage airstrike:tmp snd.e set value "airstrike:missile.rear.mid"
execute if score #appr airstrike matches 1 unless score #sph airstrike matches 2 if score #var airstrike matches 1 run data modify storage airstrike:tmp snd.e set value "airstrike:missile.front.mid"
execute if score #appr airstrike matches 1 if score #sph airstrike matches 2 if score #var airstrike matches 1 run data modify storage airstrike:tmp snd.e set value "airstrike:missile.dive.mid"
execute if score #appr airstrike matches 0 if score #var airstrike matches 2 run data modify storage airstrike:tmp snd.e set value "airstrike:missile.rear.far"
execute if score #appr airstrike matches 1 unless score #sph airstrike matches 2 if score #var airstrike matches 2 run data modify storage airstrike:tmp snd.e set value "airstrike:missile.front.far"
execute if score #appr airstrike matches 1 if score #sph airstrike matches 2 if score #var airstrike matches 2 run data modify storage airstrike:tmp snd.e set value "airstrike:missile.dive.far"
