# позиция игрока (дециблоки) — одно чтение NBT за тик вместо десятков
data modify storage shahed:tmp pp set from entity @s Pos
execute store result score @s shahed_px run data get storage shahed:tmp pp[0] 10
execute store result score @s shahed_py run data get storage shahed:tmp pp[1] 10
execute store result score @s shahed_pz run data get storage shahed:tmp pp[2] 10
