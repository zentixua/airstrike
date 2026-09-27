scoreboard players remove @s shahed_test 1
scoreboard players operation #m3 shahed = @s shahed_test
scoreboard players operation #m3 shahed %= #3 shahed
execute if score #m3 shahed matches 0 if score @s shahed_test matches 20.. run playsound shahed:bb.jet.mid ambient @s ^2 ^1 ^3 1 1 0
execute if score #m3 shahed matches 0 if score @s shahed_test matches ..19 run playsound shahed:bb.fall.near ambient @s ^-2 ^1 ^3 1 1.2 0
execute if score @s shahed_test matches 0 run tellraw @s ["",{"text":"Было слышно? ","color":"gray"},{"text":"[✔ Слышу]","color":"green","clickEvent":{"action":"run_command","value":"/trigger shahed_rp set 1"},"hoverEvent":{"action":"show_text","contents":"Включить полные звуки"}},{"text":" ","color":"gray"},{"text":"[✘ Тишина]","color":"red","clickEvent":{"action":"run_command","value":"/trigger shahed_rp set 3"},"hoverEvent":{"action":"show_text","contents":"Оставить звуки из модов"}}]
