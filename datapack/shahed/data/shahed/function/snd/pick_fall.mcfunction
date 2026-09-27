execute if score #var shahed matches 0 run data modify storage shahed:tmp snd.e set value "shahed:bb.fall.near"
execute if score #var shahed matches 1 run data modify storage shahed:tmp snd.e set value "shahed:bb.fall.mid"
execute if score #var shahed matches 2 run data modify storage shahed:tmp snd.e set value "shahed:bb.fall.far"
