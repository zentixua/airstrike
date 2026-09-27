# защита от опечаток в настройках: слишком большой взрыв подвесит сервер
execute store result score #cp shahed run data get storage shahed:cfg power
execute if score #cp shahed matches ..0 run data modify storage shahed:cfg power set value 12
execute if score #cp shahed matches 61.. run data modify storage shahed:cfg power set value 60
execute store result score #cp shahed run data get storage shahed:cfg missile_power
execute if score #cp shahed matches ..0 run data modify storage shahed:cfg missile_power set value 20
execute if score #cp shahed matches 61.. run data modify storage shahed:cfg missile_power set value 60
execute store result score #cp shahed run data get storage shahed:cfg bunker_power
execute if score #cp shahed matches ..0 run data modify storage shahed:cfg bunker_power set value 20
execute if score #cp shahed matches 61.. run data modify storage shahed:cfg bunker_power set value 60
