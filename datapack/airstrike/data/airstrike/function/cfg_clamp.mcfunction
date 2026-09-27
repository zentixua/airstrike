# защита от опечаток в настройках: слишком большой взрыв подвесит сервер
execute store result score #cp airstrike run data get storage airstrike:cfg power
execute if score #cp airstrike matches ..0 run data modify storage airstrike:cfg power set value 12
execute if score #cp airstrike matches 61.. run data modify storage airstrike:cfg power set value 60
execute store result score #cp airstrike run data get storage airstrike:cfg missile_power
execute if score #cp airstrike matches ..0 run data modify storage airstrike:cfg missile_power set value 20
execute if score #cp airstrike matches 61.. run data modify storage airstrike:cfg missile_power set value 60
execute store result score #cp airstrike run data get storage airstrike:cfg bunker_power
execute if score #cp airstrike matches ..0 run data modify storage airstrike:cfg bunker_power set value 20
execute if score #cp airstrike matches 61.. run data modify storage airstrike:cfg bunker_power set value 60
