# позиция игрока (дециблоки) — одно чтение NBT за тик вместо десятков
data modify storage airstrike:tmp pp set from entity @s Pos
execute store result score @s airstrike_px run data get storage airstrike:tmp pp[0] 10
execute store result score @s airstrike_py run data get storage airstrike:tmp pp[1] 10
execute store result score @s airstrike_pz run data get storage airstrike:tmp pp[2] 10
