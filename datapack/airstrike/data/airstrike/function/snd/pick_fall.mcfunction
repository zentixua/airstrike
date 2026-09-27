execute if score #var airstrike matches 0 run data modify storage airstrike:tmp snd.e set value "airstrike:bb.fall.near"
execute if score #var airstrike matches 1 run data modify storage airstrike:tmp snd.e set value "airstrike:bb.fall.mid"
execute if score #var airstrike matches 2 run data modify storage airstrike:tmp snd.e set value "airstrike:bb.fall.far"
