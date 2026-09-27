scoreboard players remove @s airstrike_test 1
scoreboard players operation #m3 airstrike = @s airstrike_test
scoreboard players operation #m3 airstrike %= #3 airstrike
execute if score #m3 airstrike matches 0 if score @s airstrike_test matches 20.. run playsound airstrike:bb.jet.mid ambient @s ^2 ^1 ^3 1 1 0
execute if score #m3 airstrike matches 0 if score @s airstrike_test matches ..19 run playsound airstrike:bb.fall.near ambient @s ^-2 ^1 ^3 1 1.2 0
execute if score @s airstrike_test matches 0 run tellraw @s ["",{"text":"Было слышно? ","color":"gray"},{"text":"[✔ Слышу]","color":"green","clickEvent":{"action":"run_command","value":"/trigger airstrike_rp set 1"},"hoverEvent":{"action":"show_text","contents":"Включить полные звуки"}},{"text":" ","color":"gray"},{"text":"[✘ Тишина]","color":"red","clickEvent":{"action":"run_command","value":"/trigger airstrike_rp set 3"},"hoverEvent":{"action":"show_text","contents":"Оставить звуки из модов"}}]
