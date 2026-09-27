execute if score #var airstrike matches 0 run data modify storage airstrike:tmp snd.e set value "airstrike:drone.near"
execute if score #var airstrike matches 1 run data modify storage airstrike:tmp snd.e set value "airstrike:drone.mid"
execute if score #var airstrike matches 2 run data modify storage airstrike:tmp snd.e set value "airstrike:drone.far"
